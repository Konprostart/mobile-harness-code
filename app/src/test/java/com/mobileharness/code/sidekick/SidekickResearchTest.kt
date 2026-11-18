package com.mobileharness.code.sidekick

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidekickResearchTest {

    @Test
    fun parsesNewlinesAndCommasIntoDeduplicatedSources() {
        val sources = SidekickResearch.parseSources(
            "/workspace\nhttps://developer.android.com,/workspace\nhttps://openrouter.ai/docs",
        )
        assertEquals(3, sources.size)
        assertEquals(SidekickSource(SidekickSourceKind.DIRECTORY, "/workspace"), sources[0])
        assertEquals(SidekickSource(SidekickSourceKind.URL, "https://developer.android.com"), sources[1])
        assertEquals(SidekickSource(SidekickSourceKind.URL, "https://openrouter.ai/docs"), sources[2])
    }

    @Test
    fun rejectsUnsafeUrlSchemes() {
        val sources = SidekickResearch.parseSources(
            "http://example.com\nfile:///etc/passwd\ncontent://media\njavascript:alert(1)\nhttps://ok.example.com",
        )
        assertEquals(listOf(SidekickSource(SidekickSourceKind.URL, "https://ok.example.com")), sources)
    }

    @Test
    fun capsSourceList() {
        val raw = (1..100).joinToString("\n") { "/dir$it" }
        assertEquals(SidekickResearch.MAX_SOURCES, SidekickResearch.parseSources(raw).size)
    }

    @Test
    fun allowsOnlyHttpsAndBlocksLoopbackAndPrivateHosts() {
        assertTrue(SidekickResearch.isAllowedUrl("https://developer.android.com/docs"))
        assertFalse(SidekickResearch.isAllowedUrl("http://developer.android.com"))
        assertFalse(SidekickResearch.isAllowedUrl("https://localhost:8443/x"))
        assertFalse(SidekickResearch.isAllowedUrl("https://127.0.0.1/x"))
        assertFalse(SidekickResearch.isAllowedUrl("https://10.0.0.5/x"))
        assertFalse(SidekickResearch.isAllowedUrl("https://192.168.1.2/x"))
        assertFalse(SidekickResearch.isAllowedUrl("file:///etc/passwd"))
    }

    @Test
    fun collectsTextFilesAndSkipsBinary() {
        val root = createTempDirectory("sidekick-test").toFile()
        try {
            File(root, "notes.txt").writeText("hello from notes")
            val sub = File(root, "sub").apply { mkdirs() }
            File(sub, "readme.md").writeText("# readme content")
            File(root, "image.png").writeText("binary-ish")

            val context = SidekickResearch.collectDirectoryContext(root)
            assertTrue(context.contains("hello from notes"))
            assertTrue(context.contains("# readme content"))
            assertFalse(context.contains("binary-ish"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyDirectoryYieldsBlankContext() {
        val root = createTempDirectory("sidekick-empty").toFile()
        try {
            assertEquals("", SidekickResearch.collectDirectoryContext(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fetchUrlRejectsUnsafeValue() {
        assertEquals("", SidekickResearch.fetchUrl("http://example.com"))
        assertEquals("", SidekickResearch.fetchUrl("file:///etc/passwd"))
    }
}
