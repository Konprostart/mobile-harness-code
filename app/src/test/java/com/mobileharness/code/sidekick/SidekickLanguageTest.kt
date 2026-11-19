package com.mobileharness.code.sidekick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidekickLanguageTest {
    // --- normalizeChoice: an explicit choice must never degrade to "auto" ---

    @Test
    fun normalizeKeepsExplicitSerbianAsSerbian() {
        assertEquals("sr-RS", SidekickLanguage.normalizeChoice("sr-RS"))
        assertEquals("sr-RS", SidekickLanguage.normalizeChoice("sr_RS"))
        assertEquals("sr-RS", SidekickLanguage.normalizeChoice(" SR-rs "))
    }

    @Test
    fun normalizeKeepsOtherSupportedTags() {
        assertEquals("en-US", SidekickLanguage.normalizeChoice("en-US"))
        assertEquals("en-GB", SidekickLanguage.normalizeChoice("en-GB"))
        assertEquals("sv-SE", SidekickLanguage.normalizeChoice("sv-SE"))
    }

    @Test
    fun normalizeMapsBlankAutoAndUnknownToAuto() {
        assertEquals(SidekickLanguage.AUTO, SidekickLanguage.normalizeChoice(null))
        assertEquals(SidekickLanguage.AUTO, SidekickLanguage.normalizeChoice(""))
        assertEquals(SidekickLanguage.AUTO, SidekickLanguage.normalizeChoice("  "))
        assertEquals(SidekickLanguage.AUTO, SidekickLanguage.normalizeChoice("auto"))
        assertEquals(SidekickLanguage.AUTO, SidekickLanguage.normalizeChoice("AUTO"))
        assertEquals(SidekickLanguage.AUTO, SidekickLanguage.normalizeChoice("de-DE"))
        assertEquals(SidekickLanguage.AUTO, SidekickLanguage.normalizeChoice("sr"))
    }

    // --- responseInstruction: the selected language drives the prompt ---

    @Test
    fun instructionForcesSerbianRegardlessOfInput() {
        val instruction = SidekickLanguage.responseInstruction("sr-RS")
        assertTrue(instruction.contains("Serbian"))
        assertTrue(instruction.contains("regardless of the language"))
    }

    @Test
    fun instructionForcesSwedishAndEnglish() {
        assertTrue(SidekickLanguage.responseInstruction("sv-SE").contains("Swedish"))
        assertTrue(SidekickLanguage.responseInstruction("en-US").contains("English"))
        assertTrue(SidekickLanguage.responseInstruction("en-GB").contains("English"))
    }

    @Test
    fun autoWithoutHintDelegatesToSameLanguage() {
        val instruction = SidekickLanguage.responseInstruction("auto", "some generic text")
        assertTrue(instruction.contains("same language"))
        assertFalse(instruction.contains("appears to be"))
    }

    @Test
    fun autoWithSerbianHintBiasesSerbian() {
        val instruction = SidekickLanguage.responseInstruction("auto", "Kako da popravim ovu grešku?")
        assertTrue(instruction.contains("Serbian"))
        assertTrue(instruction.contains("appears to be"))
    }

    @Test
    fun autoWithSwedishHintBiasesSwedish() {
        val instruction = SidekickLanguage.responseInstruction("auto", "Hej, kan du hjälpa mig att bygga?")
        assertTrue(instruction.contains("Swedish"))
        assertTrue(instruction.contains("appears to be"))
    }

    // --- detectLanguageTag: conservative, only confident signals ---

    @Test
    fun detectSerbianByDiacritics() {
        assertEquals("sr-RS", SidekickLanguage.detectLanguageTag("Napiši mi funkciju."))
        assertEquals("sr-RS", SidekickLanguage.detectLanguageTag("Ćao svima."))
    }

    @Test
    fun detectSerbianByDistinctiveWordsWithoutDiacritics() {
        assertEquals("sr-RS", SidekickLanguage.detectLanguageTag("kako da uradim ovo"))
        assertEquals("sr-RS", SidekickLanguage.detectLanguageTag("molim te pomozi"))
    }

    @Test
    fun detectSwedishByDiacritics() {
        assertEquals("sv-SE", SidekickLanguage.detectLanguageTag("Det är bra."))
        assertEquals("sv-SE", SidekickLanguage.detectLanguageTag("Å Ä Ö"))
    }

    @Test
    fun detectSwedishByDistinctiveWords() {
        assertEquals("sv-SE", SidekickLanguage.detectLanguageTag("hej kan du hjälpa"))
    }

    @Test
    fun detectReturnsNullWhenAmbiguous() {
        assertNull(SidekickLanguage.detectLanguageTag(""))
        assertNull(SidekickLanguage.detectLanguageTag("   "))
        assertNull(SidekickLanguage.detectLanguageTag("hello world build an app"))
    }
}
