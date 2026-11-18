package com.mobileharness.code.sidekick

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechLanguageTest {
    @Test
    fun normalizeHandlesCommonSeparators() {
        assertEquals("sr-RS", SpeechLanguage.normalize("sr-RS"))
        assertEquals("sr-RS", SpeechLanguage.normalize("sr_RS"))
        assertEquals("en-US", SpeechLanguage.normalize(" en US "))
        assertEquals("sv-SE", SpeechLanguage.normalize("SV-se"))
    }

    @Test
    fun normalizeRejectsUnusableTags() {
        assertEquals("", SpeechLanguage.normalize(""))
        assertEquals("", SpeechLanguage.normalize("sr"))
        assertEquals("", SpeechLanguage.normalize("  "))
    }

    @Test
    fun explicitPreferredTagAlwaysWins() {
        assertEquals("sv-SE", SpeechLanguage.resolve("sv-SE", listOf("en-US", "sr-RS")))
        assertEquals("sr-RS", SpeechLanguage.resolve("sr_RS", listOf("en-US")))
    }

    @Test
    fun autoPrefersPriorityLanguagesInOrder() {
        assertEquals("sr-RS", SpeechLanguage.resolve("auto", listOf("en-US", "sr-RS")))
        assertEquals("en-US", SpeechLanguage.resolve(null, listOf("fr-FR", "en-US")))
        assertEquals("en-GB", SpeechLanguage.resolve("auto", listOf("de-DE", "en-GB", "sv-SE")))
    }

    @Test
    fun autoFallsBackToFirstValidSystemLocale() {
        assertEquals("fr-FR", SpeechLanguage.resolve(null, listOf("fr-FR", "de-DE")))
    }

    @Test
    fun autoFallsBackToDefaultWhenNothingMatches() {
        assertEquals(SpeechLanguage.DEFAULT, SpeechLanguage.resolve("auto", emptyList()))
        assertEquals(SpeechLanguage.DEFAULT, SpeechLanguage.resolve("auto", listOf("xx")))
    }
}
