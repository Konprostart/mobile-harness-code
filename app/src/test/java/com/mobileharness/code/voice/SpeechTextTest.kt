package com.mobileharness.code.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `headings lose their hashes`() {
        assertEquals(
            "Checking the build\nDetails follow.",
            SpeechText.forSpeech("## Checking the build\nDetails follow."),
        )
    }

    @Test
    fun `table becomes header value sentences`() {
        val markdown = """
            | Name | Status |
            |------|--------|
            | Build | Passing |
            | Tests | 12 |
        """.trimIndent()
        val speech = SpeechText.forSpeech(markdown)
        assertTrue(speech, speech.contains("Name: Build. Status: Passing."))
        assertTrue(speech, speech.contains("Name: Tests. Status: 12."))
        assertFalse(speech, speech.contains("|"))
        assertFalse(speech, speech.contains("---"))
    }

    @Test
    fun `fenced code is not spoken`() {
        val speech = SpeechText.forSpeech("Before.\n```kotlin\nprintln(\"hidden\")\n```\nAfter.")
        assertEquals("Before.\n\nAfter.", speech)
        assertFalse(speech.contains("println"))
    }

    @Test
    fun `inline markers and links are removed`() {
        assertEquals(
            "Bold and code and docs.",
            SpeechText.forSpeech("**Bold** and `code` and [docs](https://example.com)."),
        )
    }

    @Test
    fun `bare urls are not read`() {
        assertEquals(
            "Read link for details.",
            SpeechText.forSpeech("Read https://example.com/very/long/path for details."),
        )
    }

    @Test
    fun `bullets and task boxes lose their markers`() {
        assertEquals("one\ntwo\nrun tests", SpeechText.forSpeech("- one\n- two\n- [ ] run tests"))
    }

    @Test
    fun `hash before a number is spoken as number`() {
        assertEquals("See issue number 48.", SpeechText.forSpeech("See issue #48."))
    }

    @Test
    fun `table plumbing alone has nothing to speak`() {
        val speech = SpeechText.forSpeech("|---|---|\n***\n===")
        assertFalse(speech, SpeechText.isSpeakable(speech))
    }
}
