package com.mobileharness.code.token

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenUsageReliabilityTest {

    @Test
    fun entryDefaultsReliabilityFromEstimatedFlag() {
        val estimated = TokenUsageEntry(
            timestampMillis = 1L, providerId = "openai", model = "gpt-4o", agent = "sidekick",
            usage = TokenUsage(inputTokens = 10), tokensEstimated = true,
        )
        assertEquals(Reliability.ESTIMATED, estimated.reliability)

        val actual = TokenUsageEntry(
            timestampMillis = 1L, providerId = "openai", model = "gpt-4o", agent = "sidekick",
            usage = TokenUsage(inputTokens = 10),
        )
        assertEquals(Reliability.ACTUAL, actual.reliability)
    }

    @Test
    fun codecRoundTripsNewFields() {
        val entry = TokenUsageEntry(
            timestampMillis = 123L,
            providerId = "anthropic",
            model = "claude-sonnet-4-6",
            agent = "claude-code",
            usage = TokenUsage(inputTokens = 10, outputTokens = 5, cachedReadTokens = 2),
            requestCount = 2,
            latencyMillis = 99L,
            projectId = "project-1",
            sessionId = "sess-abc",
            task = "fix bug",
            currency = "USD",
            source = UsageSource.CLI,
            reportedCostUsd = 0.02,
            reliability = Reliability.ACTUAL,
        )
        val decoded = TokenUsageCodec.fromJson(TokenUsageCodec.toJson(listOf(entry)))
        assertEquals(entry, decoded[0])
    }

    @Test
    fun codecReadsLegacyEntriesWithDefaults() {
        // Legacy shape (no new fields) must still decode with safe defaults.
        val legacy = """[{"ts":5,"providerId":"openai","model":"gpt-4o","agent":"sidekick","input":10,"output":5,"cachedRead":0,"cacheWrite":0,"estimated":true,"requests":1}]"""
        val decoded = TokenUsageCodec.fromJson(legacy)
        assertEquals(1, decoded.size)
        assertEquals(Reliability.ESTIMATED, decoded[0].reliability)
        assertEquals("", decoded[0].projectId)
        assertEquals(UsageSource.API, decoded[0].source)
        assertEquals(null, decoded[0].reportedCostUsd)
    }

    @Test
    fun codecPreservesNotTrackedReliability() {
        val entry = TokenUsageEntry(
            timestampMillis = 9L, providerId = "deepseek-harness", model = "deepseek-chat",
            agent = "deepseek-harness", usage = TokenUsage.ZERO, reliability = Reliability.NOT_TRACKED,
            source = UsageSource.SESSION_LOG,
        )
        val decoded = TokenUsageCodec.fromJson(TokenUsageCodec.toJson(listOf(entry)))
        assertEquals(Reliability.NOT_TRACKED, decoded[0].reliability)
        assertEquals(UsageSource.SESSION_LOG, decoded[0].source)
    }
}
