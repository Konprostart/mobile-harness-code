package com.mobileharness.code.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResumePolicyTest {
    private class FakeClock {
        var now: Long = 0L
        fun tick(ms: Long) {
            now += ms
        }
    }

    @Test
    fun startsInForegroundAndNoopResumeDoesNothing() {
        val clock = FakeClock()
        val policy = SessionResumePolicy { clock.now }
        assertFalse(policy.isBackgrounded)
        val decision = policy.onForeground()
        assertEquals(SessionResumePolicy.ResumeDecision(false, false, 0L), decision)
    }

    @Test
    fun shortBackgroundRefreshesSshButNotDiagnostics() {
        val clock = FakeClock()
        val policy = SessionResumePolicy { clock.now }
        policy.onBackground()
        assertTrue(policy.isBackgrounded)
        clock.tick(SessionResumePolicy.DIAGNOSTICS_REFRESH_THRESHOLD_MS - 1)
        val decision = policy.onForeground()
        assertFalse(policy.isBackgrounded)
        assertTrue(decision.refreshSshStatus)
        assertFalse(decision.refreshDiagnostics)
        assertEquals(SessionResumePolicy.DIAGNOSTICS_REFRESH_THRESHOLD_MS - 1, decision.backgroundedDurationMillis)
    }

    @Test
    fun longBackgroundRefreshesSshAndDiagnostics() {
        val clock = FakeClock()
        val policy = SessionResumePolicy { clock.now }
        policy.onBackground()
        clock.tick(SessionResumePolicy.DIAGNOSTICS_REFRESH_THRESHOLD_MS)
        val decision = policy.onForeground()
        assertTrue(decision.refreshSshStatus)
        assertTrue(decision.refreshDiagnostics)
    }

    @Test
    fun exactlyAtThresholdRefreshesDiagnostics() {
        val clock = FakeClock()
        val policy = SessionResumePolicy { clock.now }
        policy.onBackground()
        clock.tick(SessionResumePolicy.DIAGNOSTICS_REFRESH_THRESHOLD_MS)
        assertTrue(policy.onForeground().refreshDiagnostics)
    }

    @Test
    fun repeatedBackgroundCollapsesToOneTimestamp() {
        val clock = FakeClock()
        val policy = SessionResumePolicy { clock.now }
        policy.onBackground()
        clock.tick(10_000L)
        policy.onBackground() // collapsed — timestamp unchanged
        clock.tick(5_000L)
        val decision = policy.onForeground()
        // Duration is measured from the first background edge, not the collapsed second one.
        assertEquals(15_000L, decision.backgroundedDurationMillis)
    }

    @Test
    fun resumeAfterResumeIsANoop() {
        val clock = FakeClock()
        val policy = SessionResumePolicy { clock.now }
        policy.onBackground()
        clock.tick(60_000L)
        val first = policy.onForeground()
        assertTrue(first.refreshSshStatus)
        val second = policy.onForeground()
        assertEquals(SessionResumePolicy.ResumeDecision(false, false, 0L), second)
    }

    @Test
    fun clockBackwardsNeverProducesNegativeDuration() {
        val clock = FakeClock()
        val policy = SessionResumePolicy { clock.now }
        policy.onBackground()
        clock.tick(1_000L)
        // Simulate a clock adjustment backwards (NTP/manual) while backgrounded.
        clock.now -= 5_000L
        val decision = policy.onForeground()
        assertTrue(decision.backgroundedDurationMillis >= 0L)
        assertTrue(decision.refreshSshStatus)
    }
}
