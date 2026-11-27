package com.mobileharness.code.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {

    // --- ContextWindowCatalog -----------------------------------------------------------

    @Test
    fun catalogResolvesKnownReferenceWindows() {
        assertEquals(128_000L, ContextWindowCatalog.reference("openai", "gpt-4o"))
        assertEquals(200_000L, ContextWindowCatalog.reference("anthropic", "claude-sonnet-4-5"))
        assertEquals(128_000L, ContextWindowCatalog.reference("deepseek", "deepseek-chat"))
        assertEquals(128_000L, ContextWindowCatalog.reference("deepseek", "deepseek-reasoner"))
        assertEquals(1_000_000L, ContextWindowCatalog.reference("deepseek", "deepseek-v4-flash"))
        assertEquals(1_000_000L, ContextWindowCatalog.reference("deepseek", "deepseek-v4-pro"))
    }

    @Test
    fun catalogReturnsNullForUnknownModels() {
        assertNull(ContextWindowCatalog.reference("openai", "some-unknown-model"))
        assertNull(ContextWindowCatalog.reference("acme", "gpt-4o"))
    }

    // --- ContextUsageTracker ------------------------------------------------------------

    @Test
    fun resolvePrefersOverrideThenLearnedThenReference() {
        val key = ContextWindowCatalog.key("openai", "gpt-4o")
        // Reference
        assertEquals(128_000L, ContextUsageTracker.resolveMaxTokens("openai", "gpt-4o", emptyMap(), emptyMap()))
        // Learned overrides reference
        assertEquals(64_000L, ContextUsageTracker.resolveMaxTokens("openai", "gpt-4o", emptyMap(), mapOf(key to 64_000L)))
        // User override wins over learned
        assertEquals(32_000L, ContextUsageTracker.resolveMaxTokens("openai", "gpt-4o", mapOf(key to 32_000L), mapOf(key to 64_000L)))
    }

    @Test
    fun resolveIsNullWhenUnknown() {
        assertNull(ContextUsageTracker.resolveMaxTokens("acme", "unknown", emptyMap(), emptyMap()))
    }

    @Test
    fun usageComputesPercentAndRemaining() {
        val usage = ContextUsageTracker.usageFor(
            providerId = "openai",
            model = "gpt-4o",
            usage = TokenUsage(inputTokens = 60_000, outputTokens = 4_000),
        )
        assertTrue(usage.isKnown)
        assertEquals(128_000L, usage.maxTokens)
        assertEquals(64_000L, usage.totalTokens)
        assertEquals(64_000L, usage.remainingTokens)
        assertEquals(50.0, usage.percentUsed!!, 1e-6)
    }

    @Test
    fun unknownWindowReportsUnknown() {
        val usage = ContextUsageTracker.usageFor("acme", "unknown", TokenUsage(inputTokens = 10))
        assertFalse(usage.isKnown)
        assertNull(usage.remainingTokens)
        assertNull(usage.percentUsed)
    }

    // --- ContextLimitLearner -------------------------------------------------------------

    @Test
    fun learnerParsesExplicitMaximum() {
        val evidence = ContextLimitLearner.parseOverflow("Error: maximum context length is 131072 tokens.")
        assertTrue(evidence!!.confident)
        assertEquals(131_072L, evidence.observedMaxTokens)
    }

    @Test
    fun learnerParsesTwoGroupMaximumPattern() {
        val evidence = ContextLimitLearner.parseOverflow("context length 200000 exceeds maximum 128000")
        assertTrue(evidence!!.confident)
        assertEquals(128_000L, evidence.observedMaxTokens)
    }

    @Test
    fun learnerRejectsAmbiguousError() {
        assertNull(ContextLimitLearner.parseOverflow("Request failed with a transient network error."))
        assertNull(ContextLimitLearner.parseOverflow(""))
    }

    @Test
    fun learnerRejectsImplausibleMaximum() {
        // A "maximum" of 3 tokens is not a plausible context window.
        val evidence = ContextLimitLearner.parseOverflow("maximum context length is 3 tokens")
        assertFalse(evidence!!.confident)
    }

    @Test
    fun learnerOnlyLowersExistingValue() {
        val evidence = ContextLimitLearner.parseOverflow("maximum context length is 64000 tokens")!!
        assertTrue(ContextLimitLearner.shouldUpdateLearned(evidence, null))
        assertTrue(ContextLimitLearner.shouldUpdateLearned(evidence, 128_000L)) // 64000 < 128000
        assertFalse(ContextLimitLearner.shouldUpdateLearned(evidence, 32_000L)) // 64000 > 32000
    }

    // --- ContextLimitCodec ---------------------------------------------------------------

    @Test
    fun contextLimitCodecRoundTrips() {
        val map = mapOf("openai/gpt-4o" to 128_000L, "anthropic/claude-sonnet-4-5" to 200_000L)
        val decoded = ContextLimitCodec.fromJson(ContextLimitCodec.toJson(map))
        assertEquals(map, decoded)
    }

    @Test
    fun contextLimitCodecDropsNonPositive() {
        val decoded = ContextLimitCodec.fromJson(ContextLimitCodec.toJson(mapOf("x" to 0L, "y" to -5L, "z" to 10L)))
        assertEquals(mapOf("z" to 10L), decoded)
    }

    @Test
    fun contextLimitCodecHandlesGarbage() {
        assertEquals(emptyMap<String, Long>(), ContextLimitCodec.fromJson("not json"))
    }
}
