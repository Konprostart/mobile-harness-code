package com.mobileharness.code.token

import com.mobileharness.code.model.AiAuthType
import com.mobileharness.code.model.AiCapability
import com.mobileharness.code.model.AiProvider
import com.mobileharness.code.model.AiProviderCategory
import com.mobileharness.code.model.AiWireProtocol
import com.mobileharness.code.model.ProviderProtocol
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenManagerTest {

    // --- TokenUsageParser --------------------------------------------------------------

    @Test
    fun parsesOpenAiShapeWithCachedSubset() {
        val body = """
            {"usage":{"prompt_tokens":1000,"completion_tokens":200,
              "prompt_tokens_details":{"cached_tokens":300}}}
        """.trimIndent()
        val usage = TokenUsageParser.parse(body, ProviderProtocol.OPENAI_CHAT)
        assertEquals(700L, usage.inputTokens)
        assertEquals(200L, usage.outputTokens)
        assertEquals(300L, usage.cachedReadTokens)
        assertEquals(0L, usage.cacheWriteTokens)
    }

    @Test
    fun parsesAnthropicShape() {
        val body = """
            {"usage":{"input_tokens":500,"output_tokens":100,
              "cache_read_input_tokens":400,"cache_creation_input_tokens":50}}
        """.trimIndent()
        val usage = TokenUsageParser.parse(body, ProviderProtocol.ANTHROPIC)
        assertEquals(500L, usage.inputTokens)
        assertEquals(100L, usage.outputTokens)
        assertEquals(400L, usage.cachedReadTokens)
        assertEquals(50L, usage.cacheWriteTokens)
    }

    @Test
    fun missingUsageYieldsZero() {
        assertEquals(TokenUsage.ZERO, TokenUsageParser.parse("{}", ProviderProtocol.OPENAI_CHAT))
        assertEquals(TokenUsage.ZERO, TokenUsageParser.parse("not json", ProviderProtocol.ANTHROPIC))
    }

    // --- TokenUsageEstimator -----------------------------------------------------------

    @Test
    fun estimatorUsesCharPerTokenHeuristic() {
        assertEquals(1L, TokenUsageEstimator.estimateTokens("abcd"))
        assertEquals(0L, TokenUsageEstimator.estimateTokens(""))
        assertEquals(0L, TokenUsageEstimator.estimateTokens(null))
    }

    // --- TokenCalculator / pricing -----------------------------------------------------

    @Test
    fun costComputesPerMillion() {
        val pricing = ModelPricing(inputPerMillion = 2.0, outputPerMillion = 10.0)
        val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 500_000)
        val cost = TokenCalculator.cost(usage, pricing)
        assertEquals(2.0, cost.input!!, 1e-9)
        assertEquals(5.0, cost.output!!, 1e-9)
        assertEquals(CostAvailability.KNOWN, cost.availability)
    }

    @Test
    fun costIsUnavailableWithoutPricing() {
        val cost = TokenCalculator.cost(TokenUsage(inputTokens = 100, outputTokens = 10), null)
        assertNull(cost.total)
        assertEquals(CostAvailability.UNAVAILABLE, cost.availability)
    }

    @Test
    fun partialWhenBilledCategoryUnpriced() {
        // Cached-read tokens present but no cached price → PARTIAL, never fabricated.
        val pricing = ModelPricing(inputPerMillion = 2.0, outputPerMillion = 10.0)
        val usage = TokenUsage(inputTokens = 1000, outputTokens = 100, cachedReadTokens = 500)
        val cost = TokenCalculator.cost(usage, pricing)
        assertEquals(CostAvailability.PARTIAL, cost.availability)
    }

    @Test
    fun estimatedAvailabilityWhenTokensEstimated() {
        val pricing = ModelPricing(inputPerMillion = 2.0, outputPerMillion = 10.0)
        val cost = TokenCalculator.cost(TokenUsage(inputTokens = 10, outputTokens = 10), pricing, tokensEstimated = true)
        assertEquals(CostAvailability.ESTIMATED, cost.availability)
    }

    @Test
    fun overrideWinsOverReference() {
        val overrides = mapOf(
            TokenPricingCatalog.key("openai", "gpt-4o") to ModelPricing(inputPerMillion = 1.0, outputPerMillion = 2.0),
        )
        val resolved = TokenCalculator.resolvePricing("openai", "gpt-4o", overrides)
        assertEquals(1.0, resolved!!.inputPerMillion!!, 1e-9)
    }

    @Test
    fun pricingOverrideCodecRoundTrips() {
        val map = mapOf(
            "openai/gpt-4o" to ModelPricing(inputPerMillion = 1.5, outputPerMillion = 3.5, cachedReadPerMillion = 0.2),
        )
        val json = PricingOverrideCodec.toJson(map)
        val decoded = PricingOverrideCodec.fromJson(json)
        assertEquals(1.5, decoded["openai/gpt-4o"]!!.inputPerMillion!!, 1e-9)
        assertEquals(0.2, decoded["openai/gpt-4o"]!!.cachedReadPerMillion!!, 1e-9)
        assertNull(decoded["openai/gpt-4o"]!!.cacheWritePerMillion)
    }

    // --- BudgetPolicy ------------------------------------------------------------------

    @Test
    fun budgetStatusTransitions() {
        assertEquals(BudgetStatus.UNDER, BudgetPolicy.status(5.0, 10.0, 80))
        assertEquals(BudgetStatus.WARNING, BudgetPolicy.status(8.0, 10.0, 80))
        assertEquals(BudgetStatus.REACHED, BudgetPolicy.status(10.0, 10.0, 80))
        assertEquals(BudgetStatus.UNDER, BudgetPolicy.status(100.0, null, 80))
    }

    @Test
    fun budgetOnlyBlocksWhenExplicitlyRequested() {
        assertTrue(BudgetPolicy.shouldBlock(BudgetStatus.REACHED, BudgetReachedAction.STOP_NEW_REQUESTS))
        assertFalse(BudgetPolicy.shouldBlock(BudgetStatus.REACHED, BudgetReachedAction.WARN_ONLY))
        assertFalse(BudgetPolicy.shouldBlock(BudgetStatus.WARNING, BudgetReachedAction.STOP_NEW_REQUESTS))
    }

    @Test
    fun budgetCodecRoundTrips() {
        val config = BudgetConfig(
            dailyLimit = 1.0,
            weeklyLimit = 5.0,
            perProviderLimits = mapOf("openai" to 2.0),
            warnAtPercent = 90,
            reachedAction = BudgetReachedAction.SUGGEST_CHEAPER,
        )
        val decoded = BudgetConfigCodec.fromJson(BudgetConfigCodec.toJson(config))
        assertEquals(1.0, decoded.dailyLimit!!, 1e-9)
        assertEquals(2.0, decoded.perProviderLimits["openai"]!!, 1e-9)
        assertEquals(90, decoded.warnAtPercent)
        assertEquals(BudgetReachedAction.SUGGEST_CHEAPER, decoded.reachedAction)
    }

    // --- TokenOptimizer ---------------------------------------------------------------

    private fun anthropicProvider() = AiProvider(
        id = "anthropic", name = "Anthropic", category = AiProviderCategory.OFFICIAL,
        baseUrl = "https://api.anthropic.com", wireProtocol = AiWireProtocol.ANTHROPIC_MESSAGES,
        defaultModel = "claude-sonnet-4-5", authType = AiAuthType.API_KEY,
        apiFormats = setOf(AiWireProtocol.ANTHROPIC_MESSAGES),
        capabilities = setOf(AiCapability.ANTHROPIC_MESSAGES, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
        docsUrl = "https://docs.anthropic.com",
    )

    @Test
    fun cachingRequiresOptInAndCapableProvider() {
        val off = OptimizationConfig.DEFAULT
        assertFalse(TokenOptimizer.mayCache(off, anthropicProvider(), "claude-sonnet-4-5"))
        val on = OptimizationConfig(enabled = true, promptCaching = true)
        assertTrue(TokenOptimizer.mayCache(on, anthropicProvider(), "claude-sonnet-4-5"))
    }

    @Test
    fun compactionRequiresBalancedOrAbove() {
        assertFalse(TokenOptimizer.mayCompact(OptimizationConfig(enabled = true, autoCompact = true, level = OptimizationLevel.LIGHT)))
        assertTrue(TokenOptimizer.mayCompact(OptimizationConfig(enabled = true, autoCompact = true, level = OptimizationLevel.BALANCED)))
    }

    @Test
    fun suggestCheaperModelIsFailClosed() {
        val pricingFor: (String) -> ModelPricing? = { model ->
            when (model) {
                "claude-sonnet-4-5" -> ModelPricing(outputPerMillion = 15.0)
                "claude-haiku-4-6" -> ModelPricing(outputPerMillion = 4.0)
                else -> null
            }
        }
        val suggestion = TokenOptimizer.suggestCheaperModel(
            provider = anthropicProvider(),
            currentModel = "claude-sonnet-4-5",
            requiredCapabilities = setOf(AiCapability.TOOL_CALLING),
            cheaperCandidates = listOf("claude-haiku-4-6", "some-visionless-model"),
            pricingFor = pricingFor,
        )
        assertTrue(suggestion.isActionable)
        assertEquals("claude-haiku-4-6", suggestion.suggestedModel)
    }

    @Test
    fun suggestCheaperModelRefusesWhenNoCheaperCompatible() {
        val pricingFor: (String) -> ModelPricing? = { _ -> null }
        val suggestion = TokenOptimizer.suggestCheaperModel(
            provider = anthropicProvider(),
            currentModel = "claude-sonnet-4-5",
            requiredCapabilities = setOf(AiCapability.TOOL_CALLING),
            cheaperCandidates = listOf("claude-haiku-4-6"),
            pricingFor = pricingFor,
        )
        assertFalse(suggestion.isActionable)
    }

    @Test
    fun optimizationConfigCodecRoundTrips() {
        val config = OptimizationConfig(
            enabled = true,
            level = OptimizationLevel.MAXIMUM,
            autoCompact = true,
            promptCaching = true,
            smartModelSelection = true,
            maxCostPerMillion = 12.5,
            minQualityTier = QualityTier.MEDIUM,
        )
        val decoded = OptimizationConfigCodec.fromJson(OptimizationConfigCodec.toJson(config))
        assertEquals(config, decoded)
    }

    // --- TokenContextCompactor --------------------------------------------------------

    @Test
    fun compactorPreservesFixedBlocksAndFailsIfBudgetTooSmall() {
        val blocks = listOf(
            ContextBlock(ContextPriority.SYSTEM_INSTRUCTIONS, "SYSTEM-123456", fixed = true),
            ContextBlock(ContextPriority.BACKGROUND, "lots of background text here"),
        )
        // Budget smaller than the fixed block → fail (null), caller keeps original.
        assertNull(TokenContextCompactor.compact(blocks, maxChars = 5))
        // Budget fits fixed block only (13 chars) → fixed block survives verbatim.
        val result = TokenContextCompactor.compact(blocks, maxChars = 13)!!
        assertEquals(1, result.size)
        assertEquals("SYSTEM-123456", result[0].text)
    }

    @Test
    fun compactorDropsLowerPriorityFirst() {
        val blocks = listOf(
            ContextBlock(ContextPriority.SYSTEM_INSTRUCTIONS, "AAAA", fixed = true),
            ContextBlock(ContextPriority.IMPORTANT_DECISIONS, "BBBBBBBB"),
            ContextBlock(ContextPriority.BACKGROUND, "CCCCCCCC"),
        )
        val result = TokenContextCompactor.compact(blocks, maxChars = 8)!!
        val texts = result.map { it.text }
        assertTrue(texts.contains("AAAA"))
        assertTrue(texts.any { it.startsWith("B") })
        assertTrue(TokenContextCompactor.totalChars(result) <= 8)
    }

    // --- TokenUsageStore / codec ------------------------------------------------------

    @Test
    fun usageCodecRoundTrips() {
        val entries = listOf(
            TokenUsageEntry(
                timestampMillis = 123L, providerId = "openai", model = "gpt-4o", agent = "sidekick",
                usage = TokenUsage(inputTokens = 10, outputTokens = 5, cachedReadTokens = 2),
                tokensEstimated = true, requestCount = 3, latencyMillis = 55L,
            ),
        )
        val decoded = TokenUsageCodec.fromJson(TokenUsageCodec.toJson(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun storePersistsAndTrims() {
        val dir = File.createTempFile("token", "test").let { f -> f.delete(); File(f.parentFile, f.name) }
        dir.mkdirs()
        val store = TokenUsageStore(dir, maxEntries = 2)
        store.record(TokenUsageEntry(1L, "openai", "gpt-4o", "sidekick", TokenUsage(inputTokens = 1)))
        store.record(TokenUsageEntry(2L, "openai", "gpt-4o", "sidekick", TokenUsage(inputTokens = 2)))
        store.record(TokenUsageEntry(3L, "openai", "gpt-4o", "sidekick", TokenUsage(inputTokens = 3)))
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals(2L, loaded[0].timestampMillis) // oldest trimmed
        assertEquals(3L, loaded[1].timestampMillis)
        store.clear()
        assertTrue(store.load().isEmpty())
        dir.deleteRecursively()
    }

    // --- TokenSavings ------------------------------------------------------------------

    @Test
    fun totalsAggregateAndMarkEstimated() {
        val entries = listOf(
            TokenUsageEntry(1L, "openai", "gpt-4o", "sidekick", TokenUsage(inputTokens = 100, outputTokens = 20, cachedReadTokens = 10), tokensEstimated = false),
            TokenUsageEntry(2L, "openai", "gpt-4o", "sidekick", TokenUsage(inputTokens = 50, outputTokens = 10), tokensEstimated = true),
        )
        val totals = TokenSavings.totals(entries) { _, _ -> ModelPricing(inputPerMillion = 2.0, outputPerMillion = 10.0) }
        assertEquals(150L, totals.inputTokens)
        assertEquals(30L, totals.outputTokens)
        assertEquals(10L, totals.cachedReadTokens)
        assertEquals(1, totals.estimatedEntries)
        assertEquals(2, totals.entries)
        assertTrue(totals.costKnown)
    }

    @Test
    fun savingsReportUsesRealBasisOnly() {
        val totals = TokenTotals(inputTokens = 1000, outputTokens = 100, cachedReadTokens = 200, requests = 5, totalCost = 1.0, costKnown = true)
        val report = TokenSavings.savingsReport(totals, compactionTokensSaved = 300, modelChoiceTokensSaved = 50)
        assertEquals(200L, report.tokensSavedByCache)
        assertEquals(300L, report.tokensSavedByCompaction)
        assertEquals(50L, report.tokensSavedByModelChoice)
        assertEquals(550L, report.totalTokensSaved)
        assertTrue(report.savingsBasis)
    }
}
