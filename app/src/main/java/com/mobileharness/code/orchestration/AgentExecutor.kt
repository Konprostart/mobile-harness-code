package com.mobileharness.code.orchestration

import com.mobileharness.code.token.BudgetConfig
import com.mobileharness.code.token.SpendSnapshot
import com.mobileharness.code.token.TokenOptimizationService

/**
 * Real agent-execution layer for the orchestrator (Phase 6.1).
 *
 * The pure-logic [OrchestratorEngine] owns planning, ordering, locking and aggregation but never
 * launches an agent. This package supplies the thin execution seam that the Android layer fills
 * with the existing [com.mobileharness.code.runtime.RuntimeBridge] and Sidekick paths, so the
 * orchestrator drives *real* agent runs instead of synthesizing "Completed by …" results.
 *
 * Everything here is plain JVM logic: executors are injected as suspend lambdas, which keeps this
 * package free of Android/network dependencies and makes the orchestration state machine unit
 * testable with in-memory executors.
 */

/** Result of one real agent execution, normalized for the orchestrator. */
data class AgentExecution(
    val agentId: String,
    val success: Boolean,
    val output: String,
    val error: String = "",
)

/** Per-run context handed to every executor (goal + already-succeeded step results for handoff). */
data class ExecutionContext(
    val goal: String = "",
    val priorResults: List<String> = emptyList(),
)

/** A single agent the orchestrator can actually run. */
interface AgentExecutor {
    val agentId: String
    suspend fun execute(task: OrchestrationTask, context: ExecutionContext): AgentExecution
}

/**
 * Builds the prompt an agent receives for one task: the shared goal, the results of the steps that
 * already succeeded (so a reviewer can see the implementation), then the task itself. Bounded: each
 * prior result is truncated so a long chain cannot balloon the prompt.
 */
fun buildAgentPrompt(task: OrchestrationTask, context: ExecutionContext): String = buildString {
    append("Goal: ").append(context.goal.trim()).append('\n')
    if (context.priorResults.isNotEmpty()) {
        append("\nResults of previous steps (context only, do not redo them):\n")
        context.priorResults.forEach { append("- ").append(it.trim().take(1_200)).append('\n') }
    }
    append("\nYour task (").append(task.title).append("):\n")
    append(task.description.ifBlank { task.title })
}

/** Sidekick executor: a suspend question→answer function supplied by the Android layer. */
class SidekickExecutor(
    override val agentId: String = AgentIds.SIDEKICK,
    private val ask: suspend (String) -> String,
) : AgentExecutor {
    override suspend fun execute(task: OrchestrationTask, context: ExecutionContext): AgentExecution =
        try {
            AgentExecution(agentId, true, ask(buildAgentPrompt(task, context)))
        } catch (error: Exception) {
            AgentExecution(agentId, false, "", error.message ?: "Sidekick failed")
        }
}

/**
 * CLI-agent executor (Claude Code / DeepSeek Harness / Antigravity). The Android layer supplies a
 * `launch` function that runs the real [com.mobileharness.code.runtime.RuntimeBridge.startSession]
 * and returns a normalized [AgentExecution]; this class only owns prompt building and the contract.
 */
class RuntimeBridgeExecutor(
    override val agentId: String,
    private val launch: suspend (String) -> AgentExecution,
) : AgentExecutor {
    override suspend fun execute(task: OrchestrationTask, context: ExecutionContext): AgentExecution =
        launch(buildAgentPrompt(task, context))
}

/**
 * Sequential scheduler that ties [OrchestratorEngine] to real [AgentExecutor]s.
 *
 * One [step] advances exactly one ready task through its real agent run: it assigns the task,
 * marks it running, acquires any declared file locks, executes the agent, records the real result
 * (success/failure), releases the locks and posts the result to the message bus. Independent tasks
 * are deliberately run one at a time on the phone (RAM/thermal/stability); the DAG still governs
 * order and a failed dependency blocks its dependents.
 */
class OrchestratorRunner(
    val engine: OrchestratorEngine,
    private val executors: Map<String, AgentExecutor>,
    private val profiles: List<AgentProfile> = DefaultAgentProfiles.PROFILES,
    private val locks: FileLockRegistry = engine.lockRegistry(),
    private val bus: AgentMessageBus = engine.messageBus(),
    private val optimization: TokenOptimizationService? = null,
    private val budgetConfig: BudgetConfig = BudgetConfig.DEFAULT,
    private val spendProvider: (() -> SpendSnapshot)? = null,
) {
    data class StepResult(
        val executedTaskId: String? = null,
        val agentId: String? = null,
        val success: Boolean = false,
        val output: String = "",
        val error: String = "",
        val message: String? = null,
        val unassigned: List<OrchestrationTask> = emptyList(),
        val done: Boolean = false,
        val madeProgress: Boolean = false,
        val compactionRemovedChars: Int = 0,
        val compactionApplied: Boolean = false,
        val budgetBlocked: Boolean = false,
    )

    /** Runs one ready task to completion and returns the outcome + whether the plan is done. */
    suspend fun step(context: ExecutionContext): StepResult {
        val ready = engine.readyTasks()
        if (ready.isEmpty()) {
            val done = engine.snapshot().done
            return StepResult(
                message = if (done) engine.aggregateResult() else blockedMessage(),
                done = done,
                madeProgress = false,
            )
        }
        val (assignments, unassigned) = AgentAssignment.assign(ready, profiles)
        if (assignments.isEmpty()) {
            return StepResult(
                message = "No compatible agent is available for: ${unassigned.joinToString { it.title }}.",
                unassigned = unassigned,
                done = engine.snapshot().done,
                madeProgress = false,
            )
        }
        // Sequential phone-friendly execution: run exactly one task per step.
        val assignment = assignments.first()
        val task = ready.firstOrNull { it.id == assignment.taskId } ?: return StepResult(message = "Task vanished.", madeProgress = false)
        engine.advance(listOf(task), listOf(assignment))

        // Optional budget gate: when the central token optimization service is enabled with budget
        // gating, refuse to launch the step once a configured limit (global/per-agent/per-task) is
        // reached. This is fail-safe: without the opt-in it never blocks. Only new steps are gated;
        // an already-running CLI process is never interrupted here.
        if (optimization != null && spendProvider != null) {
            val decision = optimization.checkBudget(spendProvider(), budgetConfig, assignment.agentId)
            if (decision.blocked) {
                engine.blockTask(task.id, decision.reason ?: "Budget limit reached.")
                return StepResult(
                    executedTaskId = task.id,
                    agentId = assignment.agentId,
                    success = false,
                    error = decision.reason ?: "Budget limit reached.",
                    message = decision.reason ?: "Budget limit reached.",
                    unassigned = unassigned,
                    done = engine.snapshot().done,
                    madeProgress = true,
                    budgetBlocked = true,
                )
            }
        }

        // Acquire declared file locks first; a conflict fails the task closed instead of letting
        // two agents (or two steps) mutate the same path.
        val acquired = mutableListOf<String>()
        for (path in task.lockPaths) {
            if (!locks.acquire(path, assignment.agentId)) {
                acquired.forEach { locks.release(it, assignment.agentId) }
                val conflict = "File lock conflict on \"$path\" (held by ${locks.owner(path) ?: "another step"})."
                engine.blockTask(task.id, conflict)
                return StepResult(
                    executedTaskId = task.id,
                    agentId = assignment.agentId,
                    success = false,
                    error = conflict,
                    message = conflict,
                    unassigned = unassigned,
                    done = engine.snapshot().done,
                    madeProgress = true,
                )
            }
            acquired += path
        }

        val priorResults = engine.tasks()
            .filter { it.status == TaskStatus.SUCCEEDED && it.id != task.id && it.result.isNotBlank() }
            .map { it.result }
        // Optional context shaping: reduce the handoff context before the next agent sees it.
        // When the service is off (or shaping is disabled/failed) the original context is used.
        val shape = optimization?.shapeHandoff(priorResults)
        val effectivePrior = shape?.shapedResults ?: priorResults
        val execution = executors[assignment.agentId]?.execute(task, context.copy(priorResults = effectivePrior))
            ?: AgentExecution(assignment.agentId, false, "", "No executor registered for ${assignment.agentId}.")

        acquired.forEach { locks.release(it, assignment.agentId) }
        return if (execution.success) {
            engine.completeTask(task.id, execution.output, assignment.agentId)
            bus.post(
                AgentMessage(
                    fromAgentId = assignment.agentId,
                    toAgentId = null,
                    kind = "result",
                    body = execution.output.trim().take(2_000),
                ),
            )
            StepResult(
                executedTaskId = task.id,
                agentId = assignment.agentId,
                success = true,
                output = execution.output,
                unassigned = unassigned,
                done = engine.snapshot().done,
                madeProgress = true,
                compactionRemovedChars = shape?.removedChars ?: 0,
                compactionApplied = shape?.applied ?: false,
            )
        } else {
            engine.failTask(task.id, execution.error.ifBlank { "Agent reported a failure." })
            StepResult(
                executedTaskId = task.id,
                agentId = assignment.agentId,
                success = false,
                error = execution.error,
                message = execution.error.ifBlank { "Agent reported a failure." },
                unassigned = unassigned,
                done = engine.snapshot().done,
                madeProgress = true,
                compactionRemovedChars = shape?.removedChars ?: 0,
                compactionApplied = shape?.applied ?: false,
            )
        }
    }

    private fun blockedMessage(): String {
        val failed = engine.tasks().filter { it.status == TaskStatus.FAILED }
        return if (failed.isNotEmpty()) {
            "No further progress: ${failed.joinToString { it.title }} failed and blocks its dependents. Fix or reset, then continue."
        } else {
            engine.aggregateResult()
        }
    }
}
