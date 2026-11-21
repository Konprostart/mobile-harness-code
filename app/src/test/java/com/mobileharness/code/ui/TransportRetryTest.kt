package com.mobileharness.code.ui

import com.mobileharness.code.model.RecoveryFailureInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRetryTest {
    @Test
    fun matchesConnectionErrorCaseInsensitively() {
        assertTrue(isTransportFailure("Connection error."))
        assertTrue(isTransportFailure("connection error"))
        assertTrue(isTransportFailure("CONNECTION ERROR"))
    }

    @Test
    fun matchesTransportCodeCaseInsensitively() {
        assertTrue(isTransportFailure("TRANSPORT"))
        assertTrue(isTransportFailure("transport"))
        assertTrue(isTransportFailure("Transport"))
    }

    @Test
    fun doesNotMatchAuthQuotaOrRateLimitFailures() {
        assertFalse(isTransportFailure("Invalid API key"))
        assertFalse(isTransportFailure("authentication failed"))
        assertFalse(isTransportFailure("HTTP 401"))
        assertFalse(isTransportFailure("HTTP 403"))
        assertFalse(isTransportFailure("HTTP 429"))
        assertFalse(isTransportFailure("quota exceeded"))
        assertFalse(isTransportFailure("rate limit reached"))
        assertFalse(isTransportFailure("token expired"))
    }

    @Test
    fun transportIsNotMatchedWhenAuthTermsPresent() {
        assertFalse(isTransportFailure("Connection error: quota exceeded"))
        assertFalse(isTransportFailure("TRANSPORT 429 rate limit"))
    }

    @Test
    fun apiKeyFailureRequiresGenuineAuthNotTransientLimits() {
        assertTrue(isApiKeyFailureReason("The provider rejected the saved API key."))
        assertTrue(isApiKeyFailureReason("Invalid API key"))
        assertTrue(isApiKeyFailureReason("authentication failed"))
        assertTrue(isApiKeyFailureReason("User not found. Check the API key and provider account."))
        assertTrue(isApiKeyFailureReason("HTTP 401 Unauthorized"))
        assertTrue(isApiKeyFailureReason("HTTP 403 Forbidden"))
    }

    @Test
    fun transientRateLimitQuotaAndExpiryAreNotApiKeyFailures() {
        assertFalse(isApiKeyFailureReason("HTTP 429 rate limit exceeded"))
        assertFalse(isApiKeyFailureReason("quota exceeded"))
        assertFalse(isApiKeyFailureReason("rate limit reached"))
        assertFalse(isApiKeyFailureReason("token expired"))
        assertFalse(isApiKeyFailureReason("The provider rate limit was reached. Wait a moment and try again."))
    }

    @Test
    fun retryBudgetBacksOffFiveThenFifteenSecondsThenExhausts() {
        assertEquals(5000L, transportRetryDelayMillis(0)!!)
        assertEquals(15000L, transportRetryDelayMillis(1)!!)
        assertNull(transportRetryDelayMillis(2))
        assertNull(transportRetryDelayMillis(3))
    }

    @Test
    fun rotationDelayHonorsMaxAttemptsWithCappedTail() {
        // Default (2 attempts) mirrors the legacy budget exactly.
        assertEquals(5000L, rotationDelayMillis(0, 2)!!)
        assertEquals(15000L, rotationDelayMillis(1, 2)!!)
        assertNull(rotationDelayMillis(2, 2))
        // An extended chain caps the tail at 30s and still exhausts.
        assertEquals(30000L, rotationDelayMillis(2, 4)!!)
        assertEquals(30000L, rotationDelayMillis(3, 4)!!)
        assertNull(rotationDelayMillis(4, 4))
        assertNull(rotationDelayMillis(-1, 2))
    }

    @Test
    fun stopFinalizesLocallyOnlyDuringRetryGap() {
        assertFalse(shouldFinalizeStopLocally(false))
        assertTrue(shouldFinalizeStopLocally(true))
    }

    @Test
    fun transportFailureHandlingRequiresRecoveryEvidenceNotHarnessType() {
        val evidence = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = false, toolsExecuted = false)
        assertTrue(shouldHandleTransportFailure("Connection error.", evidence))
        assertFalse(shouldHandleTransportFailure("Connection error.", null))
        assertFalse(shouldHandleTransportFailure("Invalid API key", evidence))
    }
}
