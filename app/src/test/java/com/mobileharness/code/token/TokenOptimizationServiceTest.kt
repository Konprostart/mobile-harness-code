package com.mobileharness.code.token

import com.mobileharness.code.model.AiCapability
import com.mobileharness.code.model.AiProviderCatalog
import com.mobileharness.code.orchestration.AgentCapability
import com.mobileharness.code.orchestration.AgentExecution
import com.mobileharness.code.orchestration.AgentExecutor
import com.mobileharness.code.orchestration.AgentIds
import com.mobileharness.code.orchestration.AgentProfile
import com.mobileharness.code.orchestration.ExecutionContext
import com.mobileharness.code.orchestration.OrchestrationTask
import com.mobileharness.code.orchestration.OrchestratorEngine
import com.mobileharness.code.orchestration.OrchestratorRunner
import com.mobileharness.code.orchestration.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenOptimizationServiceTest {

    // --- Config codec -----------------------------------------------------------

    @Test
    fun configCodec_roundTripsAllFields() {
        val config = TokenOptimizationServiceConfig(
            enabled = true,
            contextShaping = true,
            budgetGating = true,
            modelAdvising = false,
            contextBudgetChars = 4_000,
            perTaskBudgetUsd = 2.50,
        )
        val decoded = TokenOptimizationServiceCodec.fromJson(TokenOptimizationServiceCodec.toJson(config))
        assertEquals(config, decoded)
    }

    @Test
    fun configCodec_defaultsToAllOff() {
        val decoded = TokenOptimizationServiceCodec.fromJson("")
        assertEquals(TokenOptimizationServiceConfig.DEFAULT, decoded)
        assertFalse(decoded.enabled)
    }

    @Test
    fun configCodec_nullPerTaskBudgetRoundTrips() {
        val decoded = TokenOptimizationServiceCodec.fromJson(
            TokenOptimizationServiceCodec.toJson(TokenOptimizationServiceConfig(enabled = true)),
        )
        assertNull(decoded.perTaskBudgetUsd)
    }

    // --- ContextShaper ----------------------------------------------------------

    @Test
    fun contextShaper_reducesLongHandoff() {
        val prior = listOf("x".repeat(5_000), "short")
        val result = ContextShaper.shape(prior, maxChars = 500)
        assertTrue(result.applied)
        assertTrue(result.removedChars > 0)
        assertTrue(result.shapedResults.joinToString("").length <= 500)
        assertEquals(result.originalChars, result.shapedChars + result.removedChars)
    }

    @Test
    fun contextShaper_boundsContentToBudget() {
        val prior = listOf("a very important prior result".repeat(100))
        val result = ContextShaper.shape(prior, maxChars = 500)
        // Handoff content is bounded to the budget; the goal/task are rendered separately and never
        // enter this compaction, so they are always preserved verbatim.
        assertTrue(result.shapedResults.joinToString("").length <= 500)
    }

    @Test
    fun contextShaper_blankResultsReturnEmpty() {
        val result = ContextShaper.shape(listOf("   ", ""), maxChars = 500)
        assertFalse(result.applied)
        assertTrue(result.shapedResults.isEmpty())
    }

    // --- Service shaping ----------------------------------------------------------

    @Test
    fun service_shapeHandoff_isNoOpWhenDisabled() {
        val service = TokenOptimizationService(TokenOptimizationServiceConfig(enabled = false))
        val prior = listOf("long".repeat(500))
        val result = service.shapeHandoff(prior)
        assertFalse(result.applied)
        assertEquals(prior, result.shapedResults)
        assertEquals(0, service.ledger.snapshot().compactionEvents)
    }

    @Test
    fun service_shapeHandoff_shapesAndRecordsWhenEnabled() {
        val service = TokenOptimizationService(
            TokenOptimizationServiceConfig(enabled = true, contextShaping = true, contextBudgetChars = 500),
        )
        val result = service.shapeHandoff(listOf("y".repeat(5_000)))
        assertTrue(result.applied)
        assertTrue(result.removedChars > 0)
        assertTrue(service.ledger.snapshot().compactionEvents == 1)
        assertTrue(service.ledger.snapshot().compactionTokensEstimated > 0)
    }

    // --- BudgetGate --------------------------------------------------------------

    @Test
    fun budgetGate_noBlockUnderLimit() {
        val decision = BudgetGate.check(
            snapshot = SpendSnapshot(dailyUsd = 1.0, perAgentUsd = mapOf(AgentIds.CLAUDE_CODE to 1.0)),
            budgetConfig = BudgetConfig(dailyLimit = 10.0, perAgentLimits = mapOf(AgentIds.CLAUDE_CODE to 10.0), reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS),
            perTaskBudgetUsd = 5.0,
            agentId = AgentIds.CLAUDE_CODE,
        )
        assertFalse(decision.blocked)
        assertEquals(BudgetStatus.UNDER, decision.status)
    }

    @Test
    fun budgetGate_blocksWhenStopNewRequestsAndReached() {
        val decision = BudgetGate.check(
            snapshot = SpendSnapshot(perAgentUsd = mapOf(AgentIds.CLAUDE_CODE to 50.0)),
            budgetConfig = BudgetConfig(perAgentLimits = mapOf(AgentIds.CLAUDE_CODE to 10.0), reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS),
            perTaskBudgetUsd = null,
            agentId = AgentIds.CLAUDE_CODE,
        )
        assertTrue(decision.blocked)
        assertEquals(BudgetStatus.REACHED, decision.status)
    }

    @Test
    fun budgetGate_doesNotBlockWhenActionIsWarnOnly() {
        val decision = BudgetGate.check(
            snapshot = SpendSnapshot(dailyUsd = 100.0),
            budgetConfig = BudgetConfig(dailyLimit = 10.0, reachedAction = BudgetReachedAction.WARN_ONLY),
            perTaskBudgetUsd = null,
            agentId = null,
        )
        assertFalse(decision.blocked)
        assertEquals(BudgetStatus.REACHED, decision.status)
    }

    @Test
    fun budgetGate_enforcesPerTaskBudget() {
        val decision = BudgetGate.check(
            snapshot = SpendSnapshot(taskUsd = 8.0),
            budgetConfig = BudgetConfig(reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS),
            perTaskBudgetUsd = 5.0,
            agentId = null,
        )
        assertTrue(decision.blocked)
        assertTrue(decision.reason!!.contains("Per-task budget"))
    }

    @Test
    fun budgetGate_warnsBelowReached() {
        val decision = BudgetGate.check(
            snapshot = SpendSnapshot(dailyUsd = 9.0),
            budgetConfig = BudgetConfig(dailyLimit = 10.0, warnAtPercent = 80, reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS),
            perTaskBudgetUsd = null,
            agentId = null,
        )
        assertEquals(BudgetStatus.WARNING, decision.status)
        assertFalse(decision.blocked)
    }

    // --- Service budget gating ------------------------------------------------------

    @Test
    fun service_checkBudget_isNoOpWhenDisabled() {
        val service = TokenOptimizationService(TokenOptimizationServiceConfig(enabled = false))
        val decision = service.checkBudget(
            snapshot = SpendSnapshot(dailyUsd = 100.0),
            budgetConfig = BudgetConfig(dailyLimit = 1.0, reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS),
            agentId = null,
        )
        assertFalse(decision.blocked)
        assertEquals(BudgetStatus.UNDER, decision.status)
    }

    // --- ModelAdvisor ------------------------------------------------------------

    @Test
    fun modelAdvisor_suggestsForLightTask() {
        val provider = AiProviderCatalog.byId("openai")!!
        val pricingFor: (String) -> ModelPricing? = { model ->
            when (model) {
                "gpt-4o" -> ModelPricing(inputPerMillion = 2.50, outputPerMillion = 10.00)
                "gpt-4o-mini" -> ModelPricing(inputPerMillion = 0.15, outputPerMillion = 0.60)
                else -> null
            }
        }
        val advice = ModelAdvisor.advise(
            taskTitle = "Summarize",
            taskDescription = "summarize the diff",
            provider = provider,
            currentModel = "gpt-4o",
            requiredCapabilities = setOf(AiCapability.CHAT_COMPLETIONS),
            cheaperCandidates = listOf("gpt-4o-mini"),
            pricingFor = pricingFor,
            enabled = true,
        )
        assertTrue(advice.lightTask)
        assertEquals("gpt-4o-mini", advice.suggestion?.suggestedModel)
    }

    @Test
    fun modelAdvisor_refusesForComplexTask() {
        val provider = AiProviderCatalog.byId("openai")!!
        val advice = ModelAdvisor.advise(
            taskTitle = "Implement",
            taskDescription = "write the authentication module with end-to-end tests",
            provider = provider,
            currentModel = "gpt-4o",
            cheaperCandidates = listOf("gpt-4o-mini"),
            pricingFor = { null },
            enabled = true,
        )
        assertFalse(advice.lightTask)
        assertNull(advice.suggestion)
    }

    @Test
    fun modelAdvisor_noneWhenDisabled() {
        val provider = AiProviderCatalog.byId("openai")!!
        val advice = ModelAdvisor.advise(
            taskTitle = "Summarize",
            taskDescription = "summarize",
            provider = provider,
            currentModel = "gpt-4o",
            cheaperCandidates = listOf("gpt-4o-mini"),
            pricingFor = { ModelPricing(outputPerMillion = 1.0) },
            enabled = false,
        )
        assertNull(advice.suggestion)
    }

    // --- SavingsLedger -----------------------------------------------------------

    @Test
    fun savingsLedger_recordsAndDrains() {
        val ledger = SavingsLedger()
        ledger.recordCompaction(4_000)
        ledger.recordModelChoice(100L)
        val snap = ledger.snapshot()
        assertEquals(1, snap.compactionEvents)
        assertTrue(snap.compactionTokensEstimated > 0)
        assertEquals(100L, snap.modelChoiceTokensEstimated)

        val (compaction, modelChoice) = ledger.drain()
        assertEquals(snap.compactionTokensEstimated, compaction)
        assertEquals(100L, modelChoice)
        assertEquals(0, ledger.snapshot().compactionEvents)
        assertEquals(0L, ledger.snapshot().compactionTokensEstimated)
    }

    // --- OrchestratorRunner integration ------------------------------------------

    private class FakeExecutor(
        override val agentId: String,
        private val handler: suspend (OrchestrationTask, ExecutionContext) -> AgentExecution,
    ) : AgentExecutor {
        override suspend fun execute(task: OrchestrationTask, context: ExecutionContext): AgentExecution =
            handler(task, context)
    }

    @Test
    fun runner_shapesHandoffBetweenSteps() = runBlocking {
        val a = OrchestrationTask(id = "a", title = "Implement", description = "code", requiredCapability = AgentCapability.CODE)
        val b = OrchestrationTask(id = "b", title = "Review", description = "review", requiredCapability = AgentCapability.REVIEW, dependencies = listOf("a"))
        val engine = OrchestratorEngine(listOf(a, b))
        var receivedPrior: List<String> = emptyList()
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { task, context ->
            if (task.id == "b") receivedPrior = context.priorResults
            val output = if (task.id == "a") "x".repeat(5_000) else "reviewed"
            AgentExecution(AgentIds.CLAUDE_CODE, true, output)
        }
        val optimization = TokenOptimizationService(
            TokenOptimizationServiceConfig(enabled = true, contextShaping = true, contextBudgetChars = 500),
        )
        val runner = OrchestratorRunner(
            engine,
            mapOf(AgentIds.CLAUDE_CODE to executor),
            profiles = listOf(AgentProfile(AgentIds.CLAUDE_CODE, setOf(AgentCapability.CODE, AgentCapability.REVIEW))),
            optimization = optimization,
        )

        runner.step(ExecutionContext(goal = "g")) // runs a
        val second = runner.step(ExecutionContext(goal = "g")) // runs b

        assertTrue(second.success)
        assertTrue(second.compactionApplied)
        assertTrue(second.compactionRemovedChars > 0)
        assertEquals(1, receivedPrior.size)
        assertTrue(receivedPrior[0].length <= 500)
        assertEquals(1, optimization.ledger.snapshot().compactionEvents)
    }

    @Test
    fun runner_blocksStepWhenBudgetReached() = runBlocking {
        val a = OrchestrationTask(id = "a", title = "Implement", description = "code", requiredCapability = AgentCapability.CODE)
        val engine = OrchestratorEngine(listOf(a))
        var calls = 0
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { _, _ -> calls += 1; AgentExecution(AgentIds.CLAUDE_CODE, true, "done") }
        val optimization = TokenOptimizationService(
            TokenOptimizationServiceConfig(enabled = true, budgetGating = true),
        )
        val runner = OrchestratorRunner(
            engine,
            mapOf(AgentIds.CLAUDE_CODE to executor),
            profiles = listOf(AgentProfile(AgentIds.CLAUDE_CODE, setOf(AgentCapability.CODE))),
            optimization = optimization,
            budgetConfig = BudgetConfig(perAgentLimits = mapOf(AgentIds.CLAUDE_CODE to 1.0), reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS),
            spendProvider = { SpendSnapshot(perAgentUsd = mapOf(AgentIds.CLAUDE_CODE to 5.0)) },
        )

        val step = runner.step(ExecutionContext(goal = "g"))

        assertFalse(step.success)
        assertTrue(step.budgetBlocked)
        assertEquals(0, calls)
        assertEquals(TaskStatus.BLOCKED, engine.task("a")!!.status)
    }

    @Test
    fun runner_doesNotBlockWhenOptimizationDisabled() = runBlocking {
        val a = OrchestrationTask(id = "a", title = "Implement", description = "code", requiredCapability = AgentCapability.CODE)
        val engine = OrchestratorEngine(listOf(a))
        var calls = 0
        val executor = FakeExecutor(AgentIds.CLAUDE_CODE) { _, _ -> calls += 1; AgentExecution(AgentIds.CLAUDE_CODE, true, "done") }
        val optimization = TokenOptimizationService(TokenOptimizationServiceConfig(enabled = false))
        val runner = OrchestratorRunner(
            engine,
            mapOf(AgentIds.CLAUDE_CODE to executor),
            profiles = listOf(AgentProfile(AgentIds.CLAUDE_CODE, setOf(AgentCapability.CODE))),
            optimization = optimization,
            budgetConfig = BudgetConfig(dailyLimit = 1.0, reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS),
            spendProvider = { SpendSnapshot(dailyUsd = 100.0) },
        )

        val step = runner.step(ExecutionContext(goal = "g"))

        assertTrue(step.success)
        assertFalse(step.budgetBlocked)
        assertEquals(1, calls)
    }
}
