package com.mobileharness.code.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun `normalize folds diacritics`() {
        assertEquals("procitaj", VoiceCommandParser.normalize("Pročitaj"))
        assertEquals("zaustavi agenta", VoiceCommandParser.normalize("  Zaustavi  agenta  "))
    }

    @Test
    fun `stop agent recognized and needs confirmation`() {
        val c = VoiceCommandParser.parse("zaustavi agenta")
        assertEquals(VoiceCommandKind.STOP_AGENT, c.kind)
        assertTrue(c.requiresConfirmation)
    }

    @Test
    fun `run tests needs confirmation`() {
        assertEquals(VoiceCommandKind.RUN_TESTS, VoiceCommandParser.parse("pokreni testove").kind)
        assertTrue(VoiceCommandParser.parse("run the tests").requiresConfirmation)
    }

    @Test
    fun `open project needs confirmation`() {
        assertEquals(VoiceCommandKind.OPEN_PROJECT, VoiceCommandParser.parse("otvori projekat").kind)
        assertTrue(VoiceCommandParser.parse("otvori projekat").requiresConfirmation)
    }

    @Test
    fun `change agent needs confirmation`() {
        assertEquals(VoiceCommandKind.CHANGE_AGENT, VoiceCommandParser.parse("promeni agenta").kind)
    }

    @Test
    fun `stop speech is safe and immediate`() {
        val c = VoiceCommandParser.parse("prekini govor")
        assertEquals(VoiceCommandKind.STOP_SPEECH, c.kind)
        assertFalse(c.requiresConfirmation)
    }

    @Test
    fun `pause and resume are safe`() {
        assertEquals(VoiceCommandKind.PAUSE, VoiceCommandParser.parse("pauziraj").kind)
        assertEquals(VoiceCommandKind.RESUME, VoiceCommandParser.parse("nastavi").kind)
        assertFalse(VoiceCommandParser.parse("pauza").requiresConfirmation)
    }

    @Test
    fun `repeat and read reply are safe`() {
        assertEquals(VoiceCommandKind.REPEAT, VoiceCommandParser.parse("ponovi").kind)
        assertEquals(VoiceCommandKind.READ_REPLY, VoiceCommandParser.parse("pročitaj odgovor").kind)
    }

    @Test
    fun `english commands map identically`() {
        assertEquals(VoiceCommandKind.STOP_AGENT, VoiceCommandParser.parse("stop agent").kind)
        assertEquals(VoiceCommandKind.STOP_SPEECH, VoiceCommandParser.parse("stop talking").kind)
        assertEquals(VoiceCommandKind.REPEAT, VoiceCommandParser.parse("repeat").kind)
    }

    @Test
    fun `unrelated speech maps to none`() {
        val c = VoiceCommandParser.parse("pregledaj ovaj projekat i proveri licencu")
        assertEquals(VoiceCommandKind.NONE, c.kind)
        assertFalse(c.requiresConfirmation)
    }

    @Test
    fun `blank input maps to none`() {
        assertEquals(VoiceCommandKind.NONE, VoiceCommandParser.parse("  ").kind)
    }
}
