package com.mobileharness.code.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextChunkerTest {

    @Test
    fun `short text stays one chunk`() {
        assertEquals(listOf("Hello there."), TtsTextChunker.chunk("Hello there.", 500))
    }

    @Test
    fun `long text is split under the limit`() {
        val sentence = "This is a sentence that should stay readable. "
        val chunks = TtsTextChunker.chunk(sentence.repeat(200), 300)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 300) }
    }

    @Test
    fun `paragraphs and sentences keep their words`() {
        val text = "First paragraph has two sentences. It ends here.\n\nSecond paragraph."
        val chunks = TtsTextChunker.chunk(text, 200)
        val joined = chunks.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        assertEquals("First paragraph has two sentences. It ends here. Second paragraph.", joined)
        assertFalse(chunks.any { it.isBlank() })
    }

    @Test
    fun `unbroken text is hard split without dropping characters`() {
        val chunks = TtsTextChunker.chunk("x".repeat(1_000), 300)
        assertEquals(1_000, chunks.sumOf { it.length })
        chunks.forEach { assertTrue(it.length <= 300) }
    }

    @Test
    fun `blank input produces nothing`() {
        assertTrue(TtsTextChunker.chunk("   \n\n  ", 300).isEmpty())
    }

    @Test
    fun `symbol only lines are skipped`() {
        assertTrue(TtsTextChunker.chunk("|---|---|\n===\n***", 300).isEmpty())
    }
}
