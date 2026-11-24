package com.mobileharness.code.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutorTest {

    private class FakeExecutor(
        override val agentId: String,
        private val handler: suspend (OrchestrationTask, ExecutionContext) -> AgentExecution,
    ) : AgentExecutor {
        override suspend fun execute(task: OrchestrationTask, context: ExecutionContext): AgentExecution =
            handler(task, context)
    }

    // --- Executors ---------------------------------------------------------------

    @Test
    fun sidekickExecutor_returnsAnswer() = runBlocking {
        val executor = SidekickExecutor(ask = { "answering: $it" })
        val task = OrchestrationTask(id = "t", title = "Plan", description = "plan it")
        val result = executor.execute(task, ExecutionContext(goal = "build x"))
        assertTrue(result.success)
        assertTrue(result.output.contains("answering:"))
        assertTrue(result.output.contains("plan it"))
    }

    @Test
    fun sidekickExecutor_wrapsFailure() = runBlocking {
        val executor = SidekickExecutor(ask = { throw IllegalStateException("boom") })
        val result = executor.execute(OrchestrationTask(id = "t", title = "P", description = ""), ExecutionContext())
        assertFalse(result.success)
        assertEquals("boom", result.error)
    }

    @Test
    fun runtimeBridgeExecutor_delegatesToLaunch() = runBlocking {
        val executor = RuntimeBridgeExecutor(AgentIds.CLAUDE_CODE) { prompt ->
            AgentExecution(AgentIds.CLAUDE_CODE, true, "did: $prompt")
        }
        val result = executor.execute(OrchestrationTask(id = "t", title = "Code", description = "write"), ExecutionContext(goal = "g"))
        assertTrue(result.success)
        assertTrue(result.output.contains("write"))
    }

    @Test
    fun prompt_includesGoalPriorResultsAndTask() {
        val prompt = buildAgentPrompt(
            OrchestrationTask(id = "t", title = "Review", description = "review the code"),
            ExecutionContext(goal = "build app", priorResults = listOf("impl result")),
        )
        assertTrue(prompt.contains("build app"))
        assertTrue(prompt.contains("impl result"))
        assertTrue(prompt.contains("review the code"))
    }

    @Test
    fun prompt_truncatesLongPriorResults() {
        val longResult = "x".repeat(5_000)
        val prompt = buildAgentPrompt(
            OrchestrationTask(id = "t", title = "Review", description = "d"),
            ExecutionContext(goal = "g", priorResults = listOf(longResult)),
        )
        assertFalse(prompt.contains(longResult))
        assertTrue(prompt.contains("x".repeat(1_200)))
    }

    // --- OrchestratorRunner ------------------------------------------------------

    @Test
    fun runner_runsOneReadyTaskToCompletion() = runBlocking {
        val task = OrchestrationTask(id = "a", title = "Implement", description = "code", requiredCapability = AgentCapability.CODE)
        val engine = OrchestratorEngine(listOf(task))
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { _, _ -> AgentExecution(AgentIds.CLAUDE_CODE, true, "implemented") }
        val runner = OrchestratorRunner(engine, mapOf(AgentIds.CLAUDE_CODE to executor))

        val step = runner.step(ExecutionContext(goal = "g"))

        assertTrue(step.success)
        assertEquals("a", step.executedTaskId)
        assertEquals(AgentIds.CLAUDE_CODE, step.agentId)
        assertEquals("implemented", step.output)
        assertTrue(step.done)
        assertEquals(TaskStatus.SUCCEEDED, engine.task("a")!!.status)
        assertEquals("implemented", engine.task("a")!!.result)
    }

    @Test
    fun runner_postsResultToMessageBus() = runBlocking {
        val engine = OrchestratorEngine(listOf(OrchestrationTask(id = "a", title = "Plan", description = "")))
        val bus = engine.messageBus()
        bus.register(AgentIds.SIDEKICK)
        val runner = OrchestratorRunner(
            engine,
            mapOf(AgentIds.CLAUDE_CODE to FakeExecutor(AgentIds.CLAUDE_CODE) { _, _ -> AgentExecution(AgentIds.CLAUDE_CODE, true, "done") }),
        )
        runner.step(ExecutionContext(goal = "g"))
        val messages = bus.deliver(AgentIds.SIDEKICK)
        assertEquals(1, messages.size)
        assertEquals("result", messages[0].kind)
        assertEquals("done", messages[0].body)
    }

    @Test
    fun runner_respectsDependenciesAndHandsOffResults() = runBlocking {
        val a = OrchestrationTask(id = "a", title = "Implement", description = "code", requiredCapability = AgentCapability.CODE)
        val b = OrchestrationTask(id = "b", title = "Review", description = "review", requiredCapability = AgentCapability.REVIEW, dependencies = listOf("a"))
        val engine = OrchestratorEngine(listOf(a, b))
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { task, context ->
            val output = if (task.id == "b") {
                "review saw: ${context.priorResults.joinToString()}"
            } else "implemented"
            AgentExecution(AgentIds.CLAUDE_CODE, true, output)
        }
        val runner = OrchestratorRunner(engine, mapOf(AgentIds.CLAUDE_CODE to executor), profiles = listOf(AgentProfile(AgentIds.CLAUDE_CODE, setOf(AgentCapability.CODE, AgentCapability.REVIEW))))

        val first = runner.step(ExecutionContext(goal = "g"))
        assertEquals("a", first.executedTaskId)
        assertEquals(TaskStatus.SUCCEEDED, engine.task("a")!!.status)
        assertFalse(first.done)

        val second = runner.step(ExecutionContext(goal = "g"))
        assertEquals("b", second.executedTaskId)
        assertEquals(TaskStatus.SUCCEEDED, engine.task("b")!!.status)
        assertTrue(engine.task("b")!!.result.contains("implemented"))
        assertTrue(second.done)
    }

    @Test
    fun runner_failureBlocksDependents() = runBlocking {
        val a = OrchestrationTask(id = "a", title = "Implement", description = "")
        val b = OrchestrationTask(id = "b", title = "Review", description = "", dependencies = listOf("a"))
        val engine = OrchestratorEngine(listOf(a, b))
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { _, _ -> AgentExecution(AgentIds.CLAUDE_CODE, false, "", "compile error") }
        val runner = OrchestratorRunner(engine, mapOf(AgentIds.CLAUDE_CODE to executor))

        val first = runner.step(ExecutionContext(goal = "g"))
        assertFalse(first.success)
        assertEquals(TaskStatus.FAILED, engine.task("a")!!.status)

        val second = runner.step(ExecutionContext(goal = "g"))
        assertFalse(second.madeProgress)
        assertNull(second.executedTaskId)
        assertEquals(TaskStatus.PENDING, engine.task("b")!!.status)
        assertTrue(second.message!!.contains("blocks"))
    }

    @Test
    fun runner_blocksOnFileLockConflict() = runBlocking {
        val a = OrchestrationTask(id = "a", title = "Write A", description = "", lockPaths = listOf("src/A.kt"))
        val b = OrchestrationTask(id = "b", title = "Write B", description = "", lockPaths = listOf("src/A.kt"))
        val engine = OrchestratorEngine(listOf(a, b))
        val locks = engine.lockRegistry()
        // Another step already holds the path for a different owner.
        locks.acquire("src/A.kt", "external-agent")
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { _, _ -> AgentExecution(AgentIds.CLAUDE_CODE, true, "ok") }
        val runner = OrchestratorRunner(engine, mapOf(AgentIds.CLAUDE_CODE to executor))

        val step = runner.step(ExecutionContext(goal = "g"))

        assertFalse(step.success)
        assertEquals(TaskStatus.BLOCKED, engine.task("a")!!.status)
        assertTrue(step.error.contains("lock conflict"))
    }

    @Test
    fun runner_reportsUnassignedWhenNoCompatibleAgent() = runBlocking {
        val task = OrchestrationTask(id = "a", title = "Browse", description = "", requiredCapability = AgentCapability.BROWSER)
        val engine = OrchestratorEngine(listOf(task))
        val runner = OrchestratorRunner(engine, emptyMap(), profiles = listOf(AgentProfile("code-only", setOf(AgentCapability.CODE))))

        val step = runner.step(ExecutionContext(goal = "g"))

        assertFalse(step.madeProgress)
        assertEquals(1, step.unassigned.size)
        assertEquals(TaskStatus.PENDING, engine.task("a")!!.status)
    }

    @Test
    fun runner_releasesLocksAfterExecution() = runBlocking {
        val task = OrchestrationTask(id = "a", title = "Write", description = "", lockPaths = listOf("src/A.kt"))
        val engine = OrchestratorEngine(listOf(task))
        val locks = engine.lockRegistry()
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { _, _ -> AgentExecution(AgentIds.CLAUDE_CODE, true, "ok") }
        val runner = OrchestratorRunner(engine, mapOf(AgentIds.CLAUDE_CODE to executor))

        runner.step(ExecutionContext(goal = "g"))

        assertFalse(locks.isLocked("src/A.kt"))
    }

    @Test
    fun runner_runsExactlyOneTaskPerStep() = runBlocking {
        val a = OrchestrationTask(id = "a", title = "A", description = "")
        val b = OrchestrationTask(id = "b", title = "B", description = "")
        val engine = OrchestratorEngine(listOf(a, b))
        var calls = 0
        val executor = FakeExecutor(AgentIds.SIDEKICK) { _, _ -> calls += 1; AgentExecution(AgentIds.SIDEKICK, true, "ok") }
        val runner = OrchestratorRunner(engine, mapOf(AgentIds.SIDEKICK to executor), profiles = listOf(AgentProfile(AgentIds.SIDEKICK, emptySet())))

        runner.step(ExecutionContext(goal = "g"))

        assertEquals(1, calls)
        assertEquals(TaskStatus.SUCCEEDED, engine.task("a")!!.status)
        assertEquals(TaskStatus.PENDING, engine.task("b")!!.status)
    }
}
