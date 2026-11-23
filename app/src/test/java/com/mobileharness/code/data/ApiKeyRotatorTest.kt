package com.mobileharness.code.data

import com.mobileharness.code.network.CompletionFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyRotatorTest {
    private fun key(id: String, active: Boolean = false, enabled: Boolean = true) =
        ApiKeyInfo(id = id, name = id, isActive = active, enabled = enabled)

    @Test
    fun authFailureDisablesAndRetriesNextEnabledKey() {
        val keys = listOf(key("k1", active = true), key("k2"))
        val decision = ApiKeyRotator.decide("k1", CompletionFailureKind.AUTH, keys)
        assertTrue(decision is KeyRotationDecision.DisableAndRetry)
        assertEquals("k2", (decision as KeyRotationDecision.DisableAndRetry).nextKeyId)
    }

    @Test
    fun quotaFailureDisablesAndStopsWhenNoOtherKey() {
        val keys = listOf(key("k1", active = true))
        val decision = ApiKeyRotator.decide("k1", CompletionFailureKind.QUOTA_EXCEEDED, keys)
        assertTrue(decision is KeyRotationDecision.DisableAndStop)
    }

    @Test
    fun rateLimitKeepsKeyAndTriesAnother() {
        val keys = listOf(key("k1", active = true), key("k2"))
        val decision = ApiKeyRotator.decide("k1", CompletionFailureKind.RATE_LIMITED, keys)
        assertTrue(decision is KeyRotationDecision.RetryOtherKey)
        assertEquals("k2", (decision as KeyRotationDecision.RetryOtherKey).nextKeyId)
    }

    @Test
    fun serverErrorTriesAnotherKeyWithoutDisabling() {
        val keys = listOf(key("k1", active = true), key("k2"))
        val decision = ApiKeyRotator.decide("k1", CompletionFailureKind.SERVER_ERROR, keys)
        assertTrue(decision is KeyRotationDecision.RetryOtherKey)
    }

    @Test
    fun transportAndBadRequestFailuresKeepKey() {
        val keys = listOf(key("k1", active = true), key("k2"))
        assertEquals(KeyRotationDecision.KeepKey, ApiKeyRotator.decide("k1", CompletionFailureKind.NETWORK, keys))
        assertEquals(KeyRotationDecision.KeepKey, ApiKeyRotator.decide("k1", CompletionFailureKind.TIMEOUT, keys))
        assertEquals(KeyRotationDecision.KeepKey, ApiKeyRotator.decide("k1", CompletionFailureKind.BAD_REQUEST, keys))
    }

    @Test
    fun disabledKeysAreNeverChosen() {
        val keys = listOf(key("k1", active = true), key("k2", enabled = false), key("k3"))
        val decision = ApiKeyRotator.decide("k1", CompletionFailureKind.AUTH, keys)
        assertTrue(decision is KeyRotationDecision.DisableAndRetry)
        assertEquals("k3", (decision as KeyRotationDecision.DisableAndRetry).nextKeyId)
    }

    @Test
    fun rotationIsScopedToTheGivenKeyList() {
        // The rotator is pure over one provider's keys; another provider's keys never participate.
        val providerAKeys = listOf(key("a1", active = true), key("a2"))
        val providerBKeys = listOf(key("b1", active = true))
        val decision = ApiKeyRotator.decide("a1", CompletionFailureKind.AUTH, providerAKeys)
        assertTrue(decision is KeyRotationDecision.DisableAndRetry)
        assertEquals("a2", (decision as KeyRotationDecision.DisableAndRetry).nextKeyId)
        // b1 is not in providerA's list, so it can never be returned.
        assertNull(ApiKeyRotator.nextUsableKeyId(providerBKeys, setOf("b1")))
    }

    @Test
    fun statsLedgerRoundTrips() {
        val stats = ApiKeyStats()
            .recordSuccess()
            .recordFailure(CompletionFailureKind.AUTH)
            .recordFailure(CompletionFailureKind.RATE_LIMITED)
        val ledger = mapOf("k1" to stats)
        val decoded = ApiKeyStatsLedger.fromJson(ApiKeyStatsLedger.toJson(ledger).toString())
        assertEquals(1, decoded["k1"]?.successCount)
        assertEquals(2, decoded["k1"]?.failureCount)
        assertEquals(2, decoded["k1"]?.consecutiveFailures)
        assertEquals("RATE_LIMITED", decoded["k1"]?.lastFailureKind)
    }
}
