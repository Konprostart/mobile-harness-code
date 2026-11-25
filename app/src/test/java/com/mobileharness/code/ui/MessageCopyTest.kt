package com.mobileharness.code.ui

import com.mobileharness.code.model.ChatAttachment
import com.mobileharness.code.model.ChatMessage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCopyTest {

    private fun chatMessage(text: String, fromUser: Boolean) = ChatMessage(
        fromUser = fromUser,
        text = text,
        createdAt = Instant.ofEpochSecond(1_700_000_000L),
        attachments = listOf(
            ChatAttachment(
                displayName = "notes.txt",
                relativePath = "docs/notes.txt",
                mimeType = "text/plain",
                sizeBytes = 42L,
            ),
        ),
        workedMillis = 12_345L,
    )

    @Test
    fun mainUserCopiesOnlyTheBodyNotMetadata() {
        val message = chatMessage("fun main() = println(\"hi\")", fromUser = true)
        val copied = MessageCopy.mainUser(message)

        assertEquals("fun main() = println(\"hi\")", copied)
        // Author/role, timestamp, attachments and duration must never leak into the clipboard.
        assertFalse(copied.contains("notes.txt"))
        assertFalse(copied.contains("2023"))
        assertFalse(copied.contains("docs/notes.txt"))
    }

    @Test
    fun mainAssistantCopiesTheRenderedBodyVerbatim() {
        assertEquals(
            "Here is the answer.",
            MessageCopy.mainAssistant("Here is the answer."),
        )
    }

    @Test
    fun mainAssistantPreservesWhitespaceAndNewlines() {
        val body = "Line one\n\n  indented line  "
        assertEquals(body, MessageCopy.mainAssistant(body))
    }

    @Test
    fun mainUserCopiesEmptyTextAsEmpty() {
        val message = chatMessage("", fromUser = true)
        assertEquals("", MessageCopy.mainUser(message))
    }

    @Test
    fun sidekickCopiesOnlyTheBodyAndNeverReasoning() {
        // The Sidekick assistant keeps a body and a separate reasoning string. The UI copies
        // only the body through MessageCopy.sidekick — reasoning must stay out of the clipboard.
        val body = "Final answer: 42"
        val reasoning = "The user asked about the meaning of life."
        val copied = MessageCopy.sidekick(body)

        assertEquals(body, copied)
        assertFalse(copied.contains(reasoning))
    }

    @Test
    fun sidekickCopiesUserBodyVerbatim() {
        val body = "Optimize this prompt please"
        assertEquals(body, MessageCopy.sidekick(body))
    }

    @Test
    fun sidekickHandlesUnicode() {
        val body = "Rezultat: ✓ — 42 (srpski ćirilični текст)"
        assertTrue(MessageCopy.sidekick(body).contains("ćirilični"))
    }
}
