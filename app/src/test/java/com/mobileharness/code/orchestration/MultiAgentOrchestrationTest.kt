package com.mobileharness.code.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAgentOrchestrationTest {

    @Test
    fun planner_decomposesGoalIntoDependencyOrderedPhases() {
        val plan = TaskPlanner.decompose("Build a crypto wallet app")
        assertNull(plan.validationError)
        assertEquals(4, plan.tasks.size)
        val implement = plan.tasks.first { it.title == "Implement" }
        assertEquals(listOf("phase-plan"), implement.dependencies)
        val review = plan.tasks.first { it.title == "Review" }
        assertEquals(listOf("phase-implement", "phase-test"), review.dependencies)
    }

    @Test
    fun planner_detectsCycle() {
        val a = OrchestrationTask(id = "a", title = "A", description = "a", dependencies = listOf("b"))
        val b = OrchestrationTask(id = "b", title = "B", description = "b", dependencies = listOf("a"))
        val plan = TaskPlanner.decompose("x", listOf(a, b))
        assertNotNull(plan.validationError)
        assertTrue(plan.validationError!!.contains("cycle"))
    }

    @Test
    fun graph_findsCycleInSelfReference() {
        val a = OrchestrationTask(id = "a", title = "A", description = "a", dependencies = listOf("a"))
        val cycle = OrchestrationGraph.findCycle(listOf(a))
        assertEquals(listOf("a", "a"), cycle)
    }

    @Test
    fun graph_readyTasksRespectsDependencies() {
        val a = OrchestrationTask(id = "a", title = "A", description = "a")
        val b = OrchestrationTask(id = "b", title = "B", description = "b", dependencies = listOf("a"))
        assertEquals(listOf("a"), OrchestrationGraph.readyTasks(listOf(a, b)).map { it.id })
        val aDone = a.copy(status = TaskStatus.SUCCEEDED)
        assertEquals(listOf("b"), OrchestrationGraph.readyTasks(listOf(aDone, b)).map { it.id })
    }

    @Test
    fun assignment_assignsByCapabilityAndAvailability() {
        val ready = listOf(
            OrchestrationTask(id = "t1", title = "code", description = "", requiredCapability = AgentCapability.CODE),
            OrchestrationTask(id = "t2", title = "test", description = "", requiredCapability = AgentCapability.TEST),
        )
        val profiles = DefaultAgentProfiles.PROFILES
        val (assignments, unassigned) = AgentAssignment.assign(ready, profiles)
        assertEquals(2, assignments.size)
        assertTrue(unassigned.isEmpty())
        assertEquals(AgentIds.CLAUDE_CODE, assignments.first { it.taskId == "t1" }.agentId)
    }

    @Test
    fun assignment_reportsUnassignedWhenNoCompatibleAgent() {
        val ready = listOf(OrchestrationTask(id = "t1", title = "x", description = "", requiredCapability = AgentCapability.BROWSER))
        val profiles = listOf(AgentProfile("only-code", setOf(AgentCapability.CODE)))
        val (assignments, unassigned) = AgentAssignment.assign(ready, profiles)
        assertTrue(assignments.isEmpty())
        assertEquals(1, unassigned.size)
    }

    @Test
    fun fileLockRegistry_preventsConcurrentWriters() {
        val locks = FileLockRegistry()
        assertTrue(locks.acquire("src/Main.kt", "claude"))
        assertFalse(locks.acquire("src/Main.kt", "deepseek"))
        assertEquals("claude", locks.owner("src/Main.kt"))
        locks.release("src/Main.kt", "claude")
        assertTrue(locks.acquire("src/Main.kt", "deepseek"))
    }

    @Test
    fun conflictDetector_flagsMultiAgentPathClaims() {
        val claims = listOf("a.kt" to "claude", "a.kt" to "deepseek", "b.kt" to "claude")
        val conflicts = ConflictDetector.detect(claims)
        assertEquals(1, conflicts.size)
        assertEquals("a.kt", conflicts[0].path)
        assertEquals(setOf("claude", "deepseek"), conflicts[0].agents.toSet())
    }

    @Test
    fun orchestrator_advancesAndAggregates() {
        val a = OrchestrationTask(id = "a", title = "A", description = "", requiredCapability = AgentCapability.CODE)
        val b = OrchestrationTask(id = "b", title = "B", description = "", dependencies = listOf("a"))
        val engine = OrchestratorEngine(listOf(a, b))
        val ready = engine.readyTasks()
        assertEquals(listOf("a"), ready.map { it.id })
        val (assignments, _) = AgentAssignment.assign(ready, DefaultAgentProfiles.PROFILES)
        engine.advance(ready, assignments)
        assertEquals(TaskStatus.RUNNING, engine.task("a")!!.status)
        engine.completeTask("a", "done")
        assertEquals(TaskStatus.SUCCEEDED, engine.task("a")!!.status)
        assertEquals(listOf("b"), engine.readyTasks().map { it.id })
        engine.completeTask("b", "reviewed")
        assertTrue(engine.snapshot().done)
        val aggregate = engine.aggregateResult()
        assertTrue(aggregate.contains("A"))
        assertTrue(aggregate.contains("B"))
    }

    @Test
    fun messageBus_deliversToBroadcastAndDirect() {
        val bus = AgentMessageBus()
        bus.register("claude")
        bus.register("deepseek")
        bus.register("sidekick")
        bus.post(AgentMessage(id = "m1", fromAgentId = "orchestrator", toAgentId = null, kind = "handoff", body = "start"))
        bus.post(AgentMessage(id = "m2", fromAgentId = "claude", toAgentId = "sidekick", kind = "result", body = "done"))
        val claude = bus.deliver("claude")
        val sidekick = bus.deliver("sidekick")
        assertEquals(1, claude.size) // broadcast only (direct m2 is for sidekick)
        assertEquals("start", claude[0].body)
        assertEquals(2, sidekick.size) // broadcast + direct
        assertEquals("done", sidekick.last().body)
    }
}
