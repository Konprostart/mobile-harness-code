package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoModeTest {

    @Test
    fun recommendedMaxTokensShrinksInEcoMode() {
        assertEquals(EcoMode.NORMAL_MAX_TOKENS, EcoMode.recommendedMaxTokens(ecoEnabled = false))
        assertEquals(EcoMode.ECO_MAX_TOKENS, EcoMode.recommendedMaxTokens(ecoEnabled = true))
        assertTrue(EcoMode.ECO_MAX_TOKENS < EcoMode.NORMAL_MAX_TOKENS)
    }

    @Test
    fun quietHoursAreMidnightToSixExclusive() {
        assertTrue(EcoMode.isQuietHour(0))
        assertTrue(EcoMode.isQuietHour(3))
        assertTrue(EcoMode.isQuietHour(5))
        assertFalse(EcoMode.isQuietHour(6))
        assertFalse(EcoMode.isQuietHour(12))
        assertFalse(EcoMode.isQuietHour(23))
    }

    @Test
    fun researchLimitsShrinkInEcoMode() {
        assertEquals(EcoMode.NORMAL_RESEARCH_FILES, EcoMode.researchFileLimit(false))
        assertEquals(EcoMode.ECO_RESEARCH_FILES, EcoMode.researchFileLimit(true))
        assertEquals(EcoMode.NORMAL_RESEARCH_BYTES, EcoMode.researchByteLimit(false))
        assertEquals(EcoMode.ECO_RESEARCH_BYTES, EcoMode.researchByteLimit(true))
        assertTrue(EcoMode.ECO_RESEARCH_FILES < EcoMode.NORMAL_RESEARCH_FILES)
        assertTrue(EcoMode.ECO_RESEARCH_BYTES < EcoMode.NORMAL_RESEARCH_BYTES)
    }

    @Test
    fun backgroundPollIntervalNeverThrottlesActiveTasks() {
        // An active user task always keeps the normal cadence, regardless of mode or hour.
        assertEquals(
            EcoMode.NORMAL_POLL_INTERVAL_MS,
            EcoMode.backgroundPollIntervalMillis(ecoEnabled = true, hourOfDay = 3, hasActiveTask = true),
        )
    }

    @Test
    fun backgroundPollIntervalKeepsNormalWhenEcoOff() {
        assertEquals(
            EcoMode.NORMAL_POLL_INTERVAL_MS,
            EcoMode.backgroundPollIntervalMillis(ecoEnabled = false, hourOfDay = 3, hasActiveTask = false),
        )
    }

    @Test
    fun backgroundPollIntervalRelaxesAtNightInEcoMode() {
        assertEquals(
            EcoMode.QUIET_POLL_INTERVAL_MS,
            EcoMode.backgroundPollIntervalMillis(ecoEnabled = true, hourOfDay = 2, hasActiveTask = false),
        )
        assertEquals(
            EcoMode.ECO_POLL_INTERVAL_MS,
            EcoMode.backgroundPollIntervalMillis(ecoEnabled = true, hourOfDay = 14, hasActiveTask = false),
        )
    }
}
