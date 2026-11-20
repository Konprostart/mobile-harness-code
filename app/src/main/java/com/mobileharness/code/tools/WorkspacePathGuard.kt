package com.mobileharness.code.tools

import java.io.File
import java.nio.file.Files

/**
 * Fail-closed path resolution for the built-in workspace tools. Every file the assistant may read,
 * search or list must resolve to a canonical path *inside* the configured workspace root; anything
 * else is rejected. This is the single choke point that keeps the tools from ever escaping the
 * app-private workspace directory.
 *
 * Pure JVM logic: it depends only on `java.io.File`/`java.nio.file.Files`, so it is unit-testable
 * without an Android device or an emulator.
 */
object WorkspacePathGuard {
    sealed interface Resolved {
        data class Ok(val file: File) : Resolved
        data class Denied(val reason: String) : Resolved
    }

    /**
     * Resolves [relative] against [root] and verifies the result stays within the root's canonical
     * path. Rejects blank input, absolute paths, `..` segments, NUL/control characters, and any
     * result whose canonical path is not strictly under the root (this also blocks symlink escapes,
     * because `canonicalFile` follows the link before the prefix check).
     */
    fun resolve(root: File, relative: String): Resolved {
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull()
            ?: return Resolved.Denied("Workspace root is not accessible.")
        if (!rootCanonical.isDirectory) return Resolved.Denied("Workspace root is not a directory.")

        val rel = relative.trim()
        if (rel.isEmpty()) return Resolved.Denied("Path is blank.")
        if (containsControl(rel)) return Resolved.Denied("Path contains control characters.")
        if (rel.startsWith('/') || rel.startsWith('\\')) return Resolved.Denied("Absolute paths are not allowed.")
        if (rel.split('/', '\\').any { it == ".." }) return Resolved.Denied("Parent traversal is not allowed.")
        if (rel.contains('\u0000')) return Resolved.Denied("Path contains a NUL character.")

        val candidate = File(rootCanonical, rel)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
            ?: return Resolved.Denied("Path could not be resolved.")
        if (!isWithin(rootCanonical, canonical)) return Resolved.Denied("Path escapes the workspace root.")
        return Resolved.Ok(canonical)
    }

    /** True when [candidate] is the root or a descendant of [root] (both already canonical). */
    fun isWithin(root: File, candidate: File): Boolean {
        val rootPath = root.toPath().normalize()
        val candidatePath = candidate.toPath().normalize()
        return candidatePath.startsWith(rootPath)
    }

    /**
     * A stricter read gate: the candidate must be a regular file (not a symlink) that resolves
     * inside the root. Symlinks are rejected outright for reads so a link inside the workspace
     * cannot smuggle in an out-of-tree file even when its canonical target is in-tree.
     */
    fun isSafeReadFile(root: File, file: File): Boolean {
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return false
        return runCatching {
            file.isFile &&
                !Files.isSymbolicLink(file.toPath()) &&
                isWithin(rootCanonical, file.canonicalFile)
        }.getOrDefault(false)
    }

    private fun containsControl(value: String): Boolean = value.any { it.code < 0x20 }
}
