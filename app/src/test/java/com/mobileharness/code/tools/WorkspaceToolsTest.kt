package com.mobileharness.code.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: File, relative: String, text: String): File =
        File(root, relative).apply { parentFile.mkdirs() }.apply { writeText(text) }

    @Test
    fun listStructureRendersTree() {
        val root = tmp.root
        write(root, "src/Main.kt", "fun main() {}")
        write(root, "README.md", "# hi")
        val tree = WorkspaceTools.listStructure(root)
        assertTrue(tree.contains("src/"))
        assertTrue(tree.contains("Main.kt"))
        assertTrue(tree.contains("README.md"))
    }

    @Test
    fun listStructureMissingRootIsSafe() {
        assertEquals("(workspace root not found)", WorkspaceTools.listStructure(File(tmp.root, "nope")))
    }

    @Test
    fun searchFindsContentMatches() {
        val root = tmp.root
        write(root, "src/Main.kt", "fun hello() { println(\"HelloWorld\") }")
        write(root, "src/Other.kt", "fun other() {}")
        val result = WorkspaceTools.searchFiles(root, "helloworld")
        assertTrue(result.contains("Main.kt"))
        assertTrue(!result.contains("Other.kt"))
    }

    @Test
    fun searchFindsFilenameMatches() {
        val root = tmp.root
        write(root, "build.gradle.kts", "plugins {}")
        val result = WorkspaceTools.searchFiles(root, "build.gradle")
        assertTrue(result.contains("build.gradle.kts"))
    }

    @Test
    fun searchSkipsBinaryFiles() {
        val root = tmp.root
        File(root, "img.png").apply { writeBytes(ByteArray(4) { 0x50 }) }
        write(root, "a.txt", "target needle here")
        val result = WorkspaceTools.searchFiles(root, "needle")
        assertTrue(result.contains("a.txt"))
        assertTrue(!result.contains("img.png"))
    }

    @Test
    fun searchEmptyQueryIsSafe() {
        val root = tmp.root
        write(root, "a.txt", "x")
        assertEquals("(empty search query)", WorkspaceTools.searchFiles(root, "  "))
    }

    @Test
    fun readFileReturnsContent() {
        val root = tmp.root
        write(root, "notes.txt", "hello notes")
        assertEquals("hello notes", WorkspaceTools.readFile(root, "notes.txt"))
    }

    @Test
    fun readFileDeniesUnsafePaths() {
        val root = tmp.root
        assertTrue(WorkspaceTools.readFile(root, "../secret").startsWith("Denied:"))
        assertTrue(WorkspaceTools.readFile(root, "/etc/passwd").startsWith("Denied:"))
        assertTrue(WorkspaceTools.readFile(root, "missing.txt").startsWith("Denied:"))
    }

    @Test
    fun readFileDeniesBinary() {
        val root = tmp.root
        File(root, "img.png").writeBytes(ByteArray(4))
        assertTrue(WorkspaceTools.readFile(root, "img.png").startsWith("Denied: binary"))
    }

    @Test
    fun readFileDeniesOversizedFiles() {
        val root = tmp.root
        write(root, "big.txt", "a".repeat(70_000))
        // Over the default 64000-byte limit → denied fail-closed.
        assertTrue(WorkspaceTools.readFile(root, "big.txt").startsWith("Denied: file exceeds"))
        // Still denied under an even tighter explicit limit.
        assertTrue(WorkspaceTools.readFile(root, "big.txt", maxBytes = 200).startsWith("Denied: file exceeds"))
    }
}
