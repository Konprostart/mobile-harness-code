package com.mobileharness.code.sidekick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidekickInputTest {
    @Test
    fun textFilesAreRecognizedByMimeAndExtension() {
        assertTrue(SidekickInput.isTextFile("main.kt", "application/octet-stream"))
        assertTrue(SidekickInput.isTextFile("data.json", ""))
        assertTrue(SidekickInput.isTextFile("notes.txt", "text/plain"))
        assertTrue(SidekickInput.isTextFile("build.gradle", "application/octet-stream"))
    }

    @Test
    fun binaryFilesAreNotTreatedAsText() {
        assertFalse(SidekickInput.isTextFile("photo.png", "image/png"))
        assertFalse(SidekickInput.isTextFile("archive.zip", "application/zip"))
        assertFalse(SidekickInput.isTextFile("app.apk", "application/vnd.android.package-archive"))
    }

    @Test
    fun imageMimeIsNormalizedAndUnsupportedMimesRejected() {
        assertEquals("image/png", SidekickInput.normalizeImageMime("image/png", "a.png"))
        assertEquals("image/jpeg", SidekickInput.normalizeImageMime("image/jpg", "a.jpg"))
        assertEquals("image/jpeg", SidekickInput.normalizeImageMime(null, "photo.jpeg"))
        assertEquals("image/webp", SidekickInput.normalizeImageMime("image/webp", "a.webp"))
        assertNull(SidekickInput.normalizeImageMime("application/pdf", "doc.pdf"))
        assertNull(SidekickInput.normalizeImageMime(null, "notes.txt"))
    }

    @Test
    fun fileTextIsTruncatedAtTheLimit() {
        val text = "a".repeat(SidekickInput.MAX_FILE_TEXT_CHARS + 100)
        val truncated = SidekickInput.truncateFileText(text)
        assertTrue(truncated.length < text.length)
        assertTrue(truncated.endsWith("[truncated]"))

        val short = "hello"
        assertEquals("hello", SidekickInput.truncateFileText(short))
    }

    @Test
    fun buildUserMessageInlinesFileContent() {
        val message = SidekickInput.buildUserMessage(
            "explain this",
            listOf(
                SidekickFile("a.kt", "text/plain", "fun main() = Unit"),
                SidekickFile("b.json", "application/json", "{\"x\":1}"),
            ),
        )
        assertTrue(message.startsWith("explain this"))
        assertTrue(message.contains("a.kt"))
        assertTrue(message.contains("fun main() = Unit"))
        assertTrue(message.contains("b.json"))
        assertTrue(message.contains("{\"x\":1}"))
    }

    @Test
    fun buildUserMessageWithNoFilesIsUnchanged() {
        assertEquals("hello", SidekickInput.buildUserMessage("hello", emptyList()))
    }
}
