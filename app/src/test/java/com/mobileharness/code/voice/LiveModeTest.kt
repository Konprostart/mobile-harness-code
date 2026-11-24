package com.mobileharness.code.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveModeTest {

    @Test
    fun `transcript round trip`() {
        val exchanges = listOf(
            LiveExchange("pregledaj projekat", "Važi, krećem."),
            LiveExchange("proveri i licence", "Proveravam zavisnosti."),
        )
        val text = LiveTranscript.format(exchanges)
        assertEquals(exchanges, LiveTranscript.parse(text))
    }

    @Test
    fun `parse empty or non transcript returns empty`() {
        assertEquals(emptyList<LiveExchange>(), LiveTranscript.parse(""))
        assertEquals(emptyList<LiveExchange>(), LiveTranscript.parse("plain text"))
    }

    @Test
    fun `request includes rolling transcript`() {
        val history = listOf(LiveExchange("prvo pitanje", "prvi odgovor"))
        val req = LiveMode.requestFor(LiveModeAccess.TALK, history, "drugo pitanje")
        assertTrue(req.contains("prvo pitanje"))
        assertTrue(req.contains("prvi odgovor"))
        assertTrue(req.endsWith("drugo pitanje"))
    }

    @Test
    fun `request without history is just user text`() {
        assertEquals("hello", LiveMode.requestFor(LiveModeAccess.TALK, emptyList(), "hello"))
    }

    @Test
    fun `brief extracted from start task marker`() {
        val reply = "Krećem.\nSTART_TASK: izgradi aplikaciju i testiraj je\nAgent je pokrenut."
        assertEquals("izgradi aplikaciju i testiraj je", LiveMode.briefFrom(reply))
    }

    @Test
    fun `brief null when no marker`() {
        assertNull(LiveMode.briefFrom("samo odgovor bez markera"))
    }

    @Test
    fun `system prompts differ per access mode`() {
        val talk = LiveMode.systemPrompt(LiveModeAccess.TALK)
        val read = LiveMode.systemPrompt(LiveModeAccess.READ)
        val start = LiveMode.systemPrompt(LiveModeAccess.START_TASKS)
        assertTrue(talk.contains("no tools"))
        assertTrue(start.contains("START_TASK:"))
        assertTrue(read != talk)
        assertTrue(start != read)
    }
}
