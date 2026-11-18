package com.mobileharness.code.ui

import com.mobileharness.code.ui.theme.shouldUseDynamicColor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun dynamicColorEnabledOnAndroid12AndNewer() {
        assertTrue(shouldUseDynamicColor(sdkInt = 31, dynamicColorEnabled = true))
        assertTrue(shouldUseDynamicColor(sdkInt = 34, dynamicColorEnabled = true))
        assertTrue(shouldUseDynamicColor(sdkInt = 36, dynamicColorEnabled = true))
    }

    @Test
    fun dynamicColorDisabledBelowAndroid12() {
        assertFalse(shouldUseDynamicColor(sdkInt = 30, dynamicColorEnabled = true))
        assertFalse(shouldUseDynamicColor(sdkInt = 28, dynamicColorEnabled = true))
        assertFalse(shouldUseDynamicColor(sdkInt = 21, dynamicColorEnabled = true))
    }

    @Test
    fun dynamicColorRespectsExplicitDisable() {
        assertFalse(shouldUseDynamicColor(sdkInt = 31, dynamicColorEnabled = false))
        assertFalse(shouldUseDynamicColor(sdkInt = 36, dynamicColorEnabled = false))
    }
}
