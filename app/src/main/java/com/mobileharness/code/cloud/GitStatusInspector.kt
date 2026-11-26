package com.mobileharness.code.cloud

import java.io.File

/**
 * Git repository state for a project workspace, used to colour the GitHub status lamp honestly.
 * `checked=false` means a real `git` run could not be performed, so the UI must show a neutral
 * (unknown) state rather than green.
 */
data class GitProjectStatus(
    val hasGitDir: Boolean,
    val remoteUrl: String?,
    val checked: Boolean,
    val unsynced: Boolean,
    val error: String?,
)

/**
 * Reads a workspace's `.git` directory and, when a `git` executable is available, runs a real
 * `git status` to determine whether there are uncommitted or unpushed changes. Pure JVM; the process
 * based check is skipped (leaving [GitProjectStatus.checked]=false) when git is missing or fails.
 */
object GitStatusInspector {

    fun inspect(workspaceDir: File, runGit: Boolean = true): GitProjectStatus {
        val gitDir = File(workspaceDir, ".git")
        val hasGitDir = gitDir.exists()
        val remoteUrl = if (hasGitDir) readOriginRemote(workspaceDir) else null
        if (!hasGitDir) {
            return GitProjectStatus(
                hasGitDir = false,
                remoteUrl = null,
                checked = true,
                unsynced = false,
                error = null,
            )
        }
        if (!runGit || !hasGitExecutable()) {
            return GitProjectStatus(hasGitDir = true, remoteUrl = remoteUrl, checked = false, unsynced = false, error = null)
        }
        return runCatching {
            val unsynced = hasDirtyOrUnpushed(workspaceDir)
            GitProjectStatus(hasGitDir = true, remoteUrl = remoteUrl, checked = true, unsynced = unsynced, error = null)
        }.getOrElse { error ->
            GitProjectStatus(hasGitDir = true, remoteUrl = remoteUrl, checked = false, unsynced = false, error = error.message)
        }
    }

    /** Parses the `url` of the `origin` remote from `.git/config` (plain config or linked worktree). */
    fun readOriginRemote(workspaceDir: File): String? {
        val gitPath = File(workspaceDir, ".git")
        val configFile = when {
            gitPath.isFile -> {
                // git worktree / submodule: `.git` is a file pointing at the real dir.
                val text = runCatching { gitPath.readText() }.getOrNull() ?: return null
                val match = Regex("gitdir:\\s*(.+)").find(text) ?: return null
                File(workspaceDir, match.groupValues[1].trim()).resolve("config")
            }
            gitPath.isDirectory -> File(gitPath, "config")
            else -> return null
        }
        if (!configFile.isFile) return null
        val lines = runCatching { configFile.readLines() }.getOrNull() ?: return null
        var inOrigin = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[remote ") && trimmed.contains("origin")) {
                inOrigin = true
                continue
            }
            if (trimmed.startsWith("[") && !trimmed.startsWith("[remote ")) inOrigin = false
            if (inOrigin && trimmed.startsWith("url")) {
                val url = trimmed.substringAfter('=').trim()
                    .trim('"').trim('\'')
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun hasDirtyOrUnpushed(workspaceDir: File): Boolean {
        // Uncommitted changes: `git status --porcelain` produces output for any working-tree change.
        val dirty = runCommand(workspaceDir, "git", "status", "--porcelain")
        if (dirty != null && dirty.isNotBlank()) return true
        // Unpushed commits: `git log @{u}..` lists commits ahead of the upstream branch.
        val ahead = runCommand(workspaceDir, "git", "log", "@{u}..", "--oneline")
        if (ahead != null && ahead.isNotBlank()) return true
        return false
    }

    private fun hasGitExecutable(): Boolean = runCatching {
        val p = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor() == 0
    }.getOrDefault(false)

    private fun runCommand(dir: File, vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (p.waitFor() != 0) null else out
    }.getOrNull()
}
