package com.mobileharness.code.orchestration

import java.util.UUID

/**
 * Advanced Multi-Agent Orchestration.
 *
 * A single large user goal is decomposed into a dependency-ordered plan of [OrchestrationTask]s,
 * each assigned to an available agent by capability, and advanced through a deterministic scheduler
 * that runs independent tasks in parallel while serializing dependent ones. File ownership is
 * tracked so two agents can never simultaneously mutate the same path, conflicting results are
 * surfaced instead of silently merged, and agents exchange messages over a central bus that carries
 * only task-scoped context (never API keys or unrelated project data).
 *
 * This is pure JVM logic: the Android layer supplies the actual agent launchers (the existing
 * RuntimeBridge + Sidekick), while this package owns planning, scheduling, locking and aggregation.
 */

/** Stable agent ids shared with the rest of the app (plus Sidekick, the in-app assistant). */
object AgentIds {
    const val CLAUDE_CODE = "claude-code"
    const val DEEPSEEK_HARNESS = "deepseek-harness"
    const val ANTIGRAVITY = "antigravity"
    const val SIDEKICK = "sidekick"
    val ALL = setOf(CLAUDE_CODE, DEEPSEEK_HARNESS, ANTIGRAVITY, SIDEKICK)
}

/** Coarse capabilities an agent may advertise; assignment is honest, never assumed. */
enum class AgentCapability { CODE, REVIEW, TEST, RESEARCH, PLAN, COORDINATE, BROWSER, TERMINAL }

/** What an agent is declared to support, used by [AgentAssignment]. */
data class AgentProfile(
    val agentId: String,
    val capabilities: Set<AgentCapability>,
    val label: String = agentId,
)

/** Default capability declarations for the four supported agents (documented, not invented). */
object DefaultAgentProfiles {
    val PROFILES = listOf(
        AgentProfile(AgentIds.CLAUDE_CODE, setOf(AgentCapability.CODE, AgentCapability.REVIEW, AgentCapability.TEST, AgentCapability.RESEARCH, AgentCapability.TERMINAL, AgentCapability.PLAN)),
        AgentProfile(AgentIds.DEEPSEEK_HARNESS, setOf(AgentCapability.CODE, AgentCapability.REVIEW, AgentCapability.TEST, AgentCapability.RESEARCH, AgentCapability.TERMINAL, AgentCapability.PLAN)),
        AgentProfile(AgentIds.ANTIGRAVITY, setOf(AgentCapability.CODE, AgentCapability.REVIEW, AgentCapability.RESEARCH, AgentCapability.BROWSER, AgentCapability.PLAN)),
        AgentProfile(AgentIds.SIDEKICK, setOf(AgentCapability.RESEARCH, AgentCapability.COORDINATE, AgentCapability.BROWSER, AgentCapability.PLAN)),
    )
    fun forId(agentId: String): AgentProfile? = PROFILES.firstOrNull { it.agentId == agentId }
}

enum class TaskStatus { PENDING, QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, BLOCKED }

/** One unit of work in an orchestrated plan. [dependencies] must complete before this task runs. */
data class OrchestrationTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val dependencies: List<String> = emptyList(),
    val requiredCapability: AgentCapability? = null,
    val priority: Int = 0,
    val assignedAgentId: String? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val result: String = "",
    val error: String = "",
    val expectedOutput: String = "",
) {
    val ready: Boolean get() = status == TaskStatus.PENDING
}

/** A full plan: an ordered list of tasks plus the graph's validation result. */
data class OrchestrationPlan(
    val goal: String,
    val tasks: List<OrchestrationTask>,
    val validationError: String? = null,
)

/**
 * Decomposes a high-level goal into a dependency-ordered plan. Decomposition is deterministic and
 * rule-based (no model call in this layer): it recognizes a standard build cycle — plan, implement,
 * test, review, aggregate — and maps each phase to a task, wiring review/test to depend on
 * implementation. Callers may also pass an explicit list of tasks, which is validated as-is.
 */
object TaskPlanner {
    const val MAX_TASKS = 100
    const val MAX_TASK_DESCRIPTION_CHARS = 4_000

    private val PHASES = listOf(
        "plan" to AgentCapability.PLAN,
        "implement" to AgentCapability.CODE,
        "test" to AgentCapability.TEST,
        "review" to AgentCapability.REVIEW,
    )

    fun decompose(goal: String, explicitTasks: List<OrchestrationTask> = emptyList()): OrchestrationPlan {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return OrchestrationPlan(goal, emptyList(), "Goal is blank.")
        if (explicitTasks.isNotEmpty()) {
            return validate(trimmed, explicitTasks)
        }
        val tasks = mutableListOf<OrchestrationTask>()
        val phaseIds = mutableMapOf<String, String>()
        PHASES.forEachIndexed { index, (phase, capability) ->
            val id = "phase-$phase"
            phaseIds[phase] = id
            val deps = when (phase) {
                "implement" -> listOfNotNull(phaseIds["plan"])
                "test" -> listOfNotNull(phaseIds["implement"])
                "review" -> listOfNotNull(phaseIds["implement"], phaseIds["test"])
                else -> emptyList()
            }
            tasks += OrchestrationTask(
                id = id,
                title = phase.replaceFirstChar { it.uppercaseChar() },
                description = "$phase the goal: $trimmed",
                dependencies = deps,
                requiredCapability = capability,
                priority = PHASES.size - index,
            )
        }
        return validate(trimmed, tasks)
    }

    fun validate(goal: String, tasks: List<OrchestrationTask>): OrchestrationPlan {
        if (tasks.size > MAX_TASKS) return OrchestrationPlan(goal, tasks, "Too many tasks (max $MAX_TASKS).")
        val ids = tasks.map { it.id }.toSet()
        if (ids.size != tasks.size) return OrchestrationPlan(goal, tasks, "Duplicate task ids.")
        val cycle = OrchestrationGraph.findCycle(tasks) ?: return OrchestrationPlan(goal, tasks)
        return OrchestrationPlan(goal, tasks, "Dependency cycle detected at: ${cycle.joinToString(" -> ")}")
    }
}

/** Validates and traverses the task dependency graph (DAG) without executing anything. */
object OrchestrationGraph {
    /** Returns the first cycle (a list of task ids) or null when the graph is acyclic. */
    fun findCycle(tasks: List<OrchestrationTask>): List<String>? {
        val byId = tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val stack = mutableListOf<String>()

        fun visit(id: String): List<String>? {
            if (id in visiting) {
                val start = stack.indexOf(id).coerceAtLeast(0)
                return stack.drop(start) + id
            }
            if (id in visited) return null
            visiting += id
            stack += id
            val task = byId[id] ?: run { visiting -= id; stack.removeAt(stack.lastIndex); visited += id; return null }
            for (dep in task.dependencies) {
                if (dep !in byId) continue
                visit(dep)?.let { return it }
            }
            stack.removeAt(stack.lastIndex)
            visiting -= id
            visited += id
            return null
        }

        for (task in tasks) {
            if (task.id !in visited) {
                visit(task.id)?.let { return it }
            }
        }
        return null
    }

    /** True when every dependency of [task] is completed (successful). */
    fun dependenciesMet(task: OrchestrationTask, tasksById: Map<String, OrchestrationTask>): Boolean =
        task.dependencies.all { dep -> tasksById[dep]?.status == TaskStatus.SUCCEEDED }

    /** Tasks that are pending and whose dependencies are all satisfied (the runnable frontier). */
    fun readyTasks(tasks: List<OrchestrationTask>): List<OrchestrationTask> {
        val byId = tasks.associateBy { it.id }
        return tasks.filter { it.status == TaskStatus.PENDING && dependenciesMet(it, byId) }
            .sortedWith(compareByDescending<OrchestrationTask> { it.priority }.thenBy { it.title })
    }

    /** Whether any task has failed (a failed dependency blocks its dependents). */
    fun anyFailed(tasks: List<OrchestrationTask>): Boolean = tasks.any { it.status == TaskStatus.FAILED }
}

/** Assigns ready tasks to available agents by required capability and availability. */
object AgentAssignment {
    data class Assignment(val taskId: String, val agentId: String, val reason: String)

    /**
     * Assigns each ready task to an available agent that supports the required capability (or any
     * capable agent when no capability is required). Returns one assignment per ready task and the
     * tasks that could not be assigned because no compatible agent was free.
     */
    fun assign(
        readyTasks: List<OrchestrationTask>,
        profiles: List<AgentProfile>,
        busyAgentIds: Set<String> = emptySet(),
    ): Pair<List<Assignment>, List<OrchestrationTask>> {
        val assignments = mutableListOf<Assignment>()
        val unassigned = mutableListOf<OrchestrationTask>()
        val available = profiles.filter { it.agentId !in busyAgentIds }.toMutableList()
        for (task in readyTasks) {
            val required = task.requiredCapability
            val chosen = if (required != null) {
                available.firstOrNull { required in it.capabilities }
            } else {
                available.firstOrNull()
            }
            if (chosen == null) {
                unassigned += task
                continue
            }
            available.remove(chosen)
            assignments += Assignment(
                task.id,
                chosen.agentId,
                if (required != null) "Supports $required." else "First available.",
            )
        }
        return assignments to unassigned
    }
}

/**
 * Tracks which agent owns a file path so two agents can never simultaneously mutate the same file.
 * Acquisition is exclusive and fail-closed: a conflicting acquisition is refused with the owner.
 */
class FileLockRegistry {
    data class Lock(val path: String, val ownerId: String)

    private val locks = LinkedHashMap<String, Lock>()

    /** Normalizes a path to a canonical key (separator-normalized, case-sensitive). */
    fun normalize(path: String): String = path.trim().replace('\\', '/').trimEnd('/')

    fun acquire(path: String, ownerId: String): Boolean {
        val key = normalize(path)
        if (key.isBlank()) return false
        val existing = locks[key]
        if (existing != null && existing.ownerId != ownerId) return false
        locks[key] = Lock(key, ownerId)
        return true
    }

    fun release(path: String, ownerId: String) {
        val key = normalize(path)
        if (locks[key]?.ownerId == ownerId) locks.remove(key)
    }

    fun releaseAll(ownerId: String) {
        locks.entries.removeIf { it.value.ownerId == ownerId }
    }

    fun owner(path: String): String? = locks[normalize(path)]?.ownerId

    fun isLocked(path: String): Boolean = locks.containsKey(normalize(path))

    fun snapshot(): List<Lock> = locks.values.toList()
}

/** Detects when two agents produce conflicting ownership/results on the same path. */
object ConflictDetector {
    data class Conflict(val path: String, val agents: List<String>, val reason: String)

    /** Conflicts arise when the same path is claimed by two different agents at once. */
    fun detect(claims: List<Pair<String, String>>): List<Conflict> {
        val byPath = claims.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
        return byPath.filter { it.value.size > 1 }.map { (path, agents) ->
            Conflict(path, agents, "Path claimed by ${agents.joinToString(" and ")}.")
        }.sortedBy { it.path }
    }
}

/** A scoped message between agents (or from the orchestrator). Never carries secrets. */
data class AgentMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromAgentId: String,
    val toAgentId: String?, // null = broadcast
    val kind: String, // e.g. "handoff", "review_request", "test_request", "result"
    val body: String,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/** Central message bus with per-agent inboxes; delivery is in-order and bounded. */
class AgentMessageBus(private val maxInboxSize: Int = 100) {
    private val inboxes = LinkedHashMap<String, ArrayDeque<AgentMessage>>()
    private val registered = LinkedHashSet<String>()

    /** Registers an agent so broadcasts reach it. */
    fun register(agentId: String) {
        registered += agentId
        inbox(agentId)
    }

    fun post(message: AgentMessage) {
        val targets = if (message.toAgentId == null) registered.toList() else listOf(message.toAgentId!!)
        targets.forEach { inbox(it).addLast(message) }
    }

    fun deliver(agentId: String): List<AgentMessage> {
        val inbox = inboxes[agentId] ?: return emptyList()
        val messages = inbox.toList()
        inbox.clear()
        return messages
    }

    fun pending(agentId: String): List<AgentMessage> = inboxes[agentId]?.toList() ?: emptyList()

    fun clear() {
        inboxes.clear()
        registered.clear()
    }

    private fun inbox(agentId: String): ArrayDeque<AgentMessage> {
        val existing = inboxes[agentId]
        if (existing != null) return existing
        val created = ArrayDeque<AgentMessage>()
        inboxes[agentId] = created
        while (created.size > maxInboxSize) created.removeFirst()
        return created
    }
}

/** Snapshot of the shared orchestration state, safe to render and audit. */
data class OrchestrationSnapshot(
    val tasks: List<OrchestrationTask>,
    val locks: List<FileLockRegistry.Lock>,
    val conflicts: List<ConflictDetector.Conflict>,
    val runningAgentIds: Set<String>,
) {
    val pendingCount: Int get() = tasks.count { it.status == TaskStatus.PENDING || it.status == TaskStatus.QUEUED }
    val runningCount: Int get() = tasks.count { it.status == TaskStatus.RUNNING }
    val succeededCount: Int get() = tasks.count { it.status == TaskStatus.SUCCEEDED }
    val failedCount: Int get() = tasks.count { it.status == TaskStatus.FAILED }
    val done: Boolean get() = tasks.isNotEmpty() && tasks.all { it.status in setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED) }
}

/**
 * The orchestrator: holds a plan, the file-lock registry and the message bus, and advances the
 * workflow through a deterministic schedule. The Android layer supplies agents and results; this
 * engine owns ordering, locking and aggregation so independent tasks parallelize and dependent
 * tasks serialize, with conflicts surfaced instead of silently merged.
 */
class OrchestratorEngine(
    initialTasks: List<OrchestrationTask> = emptyList(),
    private val locks: FileLockRegistry = FileLockRegistry(),
    private val bus: AgentMessageBus = AgentMessageBus(),
) {
    private val tasks = LinkedHashMap<String, OrchestrationTask>().apply {
        initialTasks.forEach { put(it.id, it) }
    }

    fun tasks(): List<OrchestrationTask> = tasks.values.toList()

    fun task(id: String): OrchestrationTask? = tasks[id]

    fun addTask(task: OrchestrationTask): Boolean {
        if (task.id.isBlank() || tasks.containsKey(task.id)) return false
        tasks[task.id] = task
        return true
    }

    fun readyTasks(): List<OrchestrationTask> = OrchestrationGraph.readyTasks(tasks())

    fun advance(
        readyTasks: List<OrchestrationTask>,
        assignments: List<AgentAssignment.Assignment>,
    ): List<OrchestrationTask> {
        val assigned = assignments.associate { it.taskId to it.agentId }
        val started = mutableListOf<OrchestrationTask>()
        for (task in readyTasks) {
            val agentId = assigned[task.id] ?: continue
            val updated = task.copy(status = TaskStatus.RUNNING, assignedAgentId = agentId)
            tasks[task.id] = updated
            started += updated
        }
        return started
    }

    fun completeTask(taskId: String, result: String, agentId: String? = null): OrchestrationTask? {
        val existing = tasks[taskId] ?: return null
        val updated = existing.copy(status = TaskStatus.SUCCEEDED, result = result, assignedAgentId = agentId ?: existing.assignedAgentId)
        tasks[taskId] = updated
        locks.releaseAll(agentId ?: existing.assignedAgentId ?: "")
        return updated
    }

    fun failTask(taskId: String, error: String): OrchestrationTask? {
        val existing = tasks[taskId] ?: return null
        val updated = existing.copy(status = TaskStatus.FAILED, error = error)
        tasks[taskId] = updated
        return updated
    }

    fun blockTask(taskId: String, reason: String): OrchestrationTask? {
        val existing = tasks[taskId] ?: return null
        val updated = existing.copy(status = TaskStatus.BLOCKED, error = reason)
        tasks[taskId] = updated
        return updated
    }

    fun cancelAll() {
        tasks.replaceAll { _, t -> if (t.status in setOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING)) t.copy(status = TaskStatus.CANCELLED) else t }
    }

    fun aggregateResult(): String {
        val ordered = tasks()
        val sb = StringBuilder()
        sb.append("Goal result:\n")
        for (task in ordered) {
            sb.append("- [").append(task.status.name).append("] ").append(task.title)
            if (task.result.isNotBlank()) sb.append(": ").append(task.result.take(200))
            if (task.error.isNotBlank()) sb.append(" — error: ").append(task.error.take(200))
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun snapshot(runningAgentIds: Set<String> = emptySet()): OrchestrationSnapshot =
        OrchestrationSnapshot(
            tasks = tasks(),
            locks = locks.snapshot(),
            conflicts = ConflictDetector.detect(locks.snapshot().map { it.path to it.ownerId }),
            runningAgentIds = runningAgentIds,
        )

    fun lockRegistry(): FileLockRegistry = locks

    fun messageBus(): AgentMessageBus = bus
}
