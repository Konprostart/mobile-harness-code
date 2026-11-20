package com.mobileharness.code.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePathGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolvesAFileInsideRoot() {
        val root = tmp.root
        File(root, "a/b.txt").apply { parentFile.mkdirs() }.writeText("hi")
        val result = WorkspacePathGuard.resolve(root, "a/b.txt")
        assertTrue(result is WorkspacePathGuard.Resolved.Ok)
        assertEquals(File(root, "a/b.txt").canonicalFile, (result as WorkspacePathGuard.Resolved.Ok).file)
    }

    @Test
    fun rejectsBlankAndAbsolutePaths() {
        val root = tmp.root
        assertTrue(WorkspacePathGuard.resolve(root, "") is WorkspacePathGuard.Resolved.Denied)
        assertTrue(WorkspacePathGuard.resolve(root, "   ") is WorkspacePathGuard.Resolved.Denied)
        assertTrue(WorkspacePathGuard.resolve(root, "/etc/passwd") is WorkspacePathGuard.Resolved.Denied)
    }

    @Test
    fun rejectsParentTraversal() {
        val root = tmp.root
        assertTrue(WorkspacePathGuard.resolve(root, "../secret") is WorkspacePathGuard.Resolved.Denied)
        assertTrue(WorkspacePathGuard.resolve(root, "a/../../secret") is WorkspacePathGuard.Resolved.Denied)
        assertTrue(WorkspacePathGuard.resolve(root, "a\\..\\..\\secret") is WorkspacePathGuard.Resolved.Denied)
    }

    @Test
    fun rejectsControlCharactersAndNul() {
        val root = tmp.root
        assertTrue(WorkspacePathGuard.resolve(root, "a\u0000b") is WorkspacePathGuard.Resolved.Denied)
        assertTrue(WorkspacePathGuard.resolve(root, "a\nb") is WorkspacePathGuard.Resolved.Denied)
    }

    @Test
    fun rejectsSymlinkEscape() {
        val root = tmp.root
        val outside = File(tmp.root.parentFile, "outside-secret.txt")
        outside.writeText("secret")
        val link = File(root, "link.txt")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        val result = WorkspacePathGuard.resolve(root, "link.txt")
        // Either the symlink resolves outside (denied) or the platform cannot create it; both are
        // fail-closed. On Linux it must be denied.
        if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
            assertTrue(result is WorkspacePathGuard.Resolved.Denied)
        }
    }

    @Test
    fun isSafeReadFileRejectsSymlink() {
        val root = tmp.root
        val real = File(root, "real.txt").apply { writeText("x") }
        val link = File(root, "link.txt")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath()) }
        assertTrue(WorkspacePathGuard.isSafeReadFile(root, real))
        if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
            assertTrue(!WorkspacePathGuard.isSafeReadFile(root, link))
        }
    }

    @Test
    fun isWithinMatchesRootAndDescendants() {
        val root = tmp.root.canonicalFile
        val child = File(root, "sub/x").canonicalFile
        assertTrue(WorkspacePathGuard.isWithin(root, root))
        assertTrue(WorkspacePathGuard.isWithin(root, child))
        assertTrue(!WorkspacePathGuard.isWithin(root, tmp.root.parentFile.canonicalFile))
    }
}
