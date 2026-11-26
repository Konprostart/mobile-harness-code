package com.mobileharness.code.cloud

import com.mobileharness.code.model.Project
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectStatusEngineTest {

    private fun project(
        gitRemoteUrl: String? = null,
        signingKeyIds: List<String> = emptyList(),
    ): Project = Project(
        name = "Demo",
        description = "",
        language = "Kotlin",
        gitRemoteUrl = gitRemoteUrl,
        signingKeyIds = signingKeyIds,
    )

    private fun git(
        hasGitDir: Boolean = true,
        remoteUrl: String? = null,
        checked: Boolean = true,
        unsynced: Boolean = false,
        error: String? = null,
    ) = GitProjectStatus(hasGitDir, remoteUrl, checked, unsynced, error)

    // ---- GitHub ----

    @Test
    fun githubGreenOnlyWhenSynced() {
        val statuses = ProjectStatusEngine.compute(
            project(gitRemoteUrl = "https://github.com/o/r.git"),
            git(remoteUrl = "https://github.com/o/r.git", checked = true, unsynced = false),
            setOf(), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.GREEN, statuses.github.level)
        assertEquals("synced", statuses.github.code)
    }

    @Test
    fun githubRedWhenUnsynced() {
        val statuses = ProjectStatusEngine.compute(
            project(gitRemoteUrl = "https://github.com/o/r.git"),
            git(remoteUrl = "https://github.com/o/r.git", checked = true, unsynced = true),
            setOf(), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.RED, statuses.github.level)
        assertEquals("unsynced", statuses.github.code)
    }

    @Test
    fun githubRedWhenNoRemote() {
        val statuses = ProjectStatusEngine.compute(
            project(gitRemoteUrl = null),
            git(remoteUrl = null, checked = true, unsynced = false),
            setOf(), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.RED, statuses.github.level)
        assertEquals("not_connected", statuses.github.code)
    }

    @Test
    fun githubUnknownWhenCheckFailed() {
        val statuses = ProjectStatusEngine.compute(
            project(gitRemoteUrl = "https://github.com/o/r.git"),
            git(remoteUrl = "https://github.com/o/r.git", checked = false, error = "boom"),
            setOf(), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.UNKNOWN, statuses.github.level)
        assertEquals("check_failed", statuses.github.code)
    }

    @Test
    fun githubUnknownWhenNotVerified() {
        val statuses = ProjectStatusEngine.compute(
            project(gitRemoteUrl = "https://github.com/o/r.git"),
            git(remoteUrl = "https://github.com/o/r.git", checked = false),
            setOf(), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.UNKNOWN, statuses.github.level)
        assertEquals("not_verified", statuses.github.code)
    }

    @Test
    fun githubUsesProjectRemoteAsFallback() {
        val statuses = ProjectStatusEngine.compute(
            project(gitRemoteUrl = "https://github.com/o/r.git"),
            git(remoteUrl = null, checked = true, unsynced = false),
            setOf(), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.GREEN, statuses.github.level)
    }

    // ---- Signiffia ----

    @Test
    fun signiffiaRedWhenNoKey() {
        val statuses = ProjectStatusEngine.compute(
            project(signingKeyIds = emptyList()), git(), setOf("k1"), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.RED, statuses.signiffia.level)
        assertEquals("no_key", statuses.signiffia.code)
    }

    @Test
    fun signiffiaRedWhenBoundKeyMissing() {
        val statuses = ProjectStatusEngine.compute(
            project(signingKeyIds = listOf("k1", "k2")), git(), setOf("k1"), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.RED, statuses.signiffia.level)
        assertEquals("missing_key", statuses.signiffia.code)
    }

    @Test
    fun signiffiaGreenWhenAllKeysPresent() {
        val statuses = ProjectStatusEngine.compute(
            project(signingKeyIds = listOf("k1", "k2")), git(), setOf("k1", "k2"), ProjectCloudBackup(),
        )
        assertEquals(ProjectStatusLevel.GREEN, statuses.signiffia.level)
        assertEquals("keys_saved", statuses.signiffia.code)
    }

    // ---- Cloud ----

    @Test
    fun cloudRedWhenNotConfigured() {
        val statuses = ProjectStatusEngine.compute(project(), git(), setOf(), ProjectCloudBackup(enabled = false))
        assertEquals(ProjectStatusLevel.RED, statuses.cloud.level)
        assertEquals("not_configured", statuses.cloud.code)
    }

    @Test
    fun cloudRedWhenNoBackupYet() {
        val statuses = ProjectStatusEngine.compute(project(), git(), setOf(), ProjectCloudBackup(enabled = true))
        assertEquals(ProjectStatusLevel.RED, statuses.cloud.level)
        assertEquals("no_backup", statuses.cloud.code)
    }

    @Test
    fun cloudRedWhenLastBackupFailed() {
        val statuses = ProjectStatusEngine.compute(
            project(), git(), setOf(),
            ProjectCloudBackup(enabled = true, lastBackupAt = 123L, lastBackupError = "network down"),
        )
        assertEquals(ProjectStatusLevel.RED, statuses.cloud.level)
        assertEquals("backup_failed", statuses.cloud.code)
    }

    @Test
    fun cloudGreenOnlyAfterSuccessfulBackup() {
        val statuses = ProjectStatusEngine.compute(
            project(), git(), setOf(),
            ProjectCloudBackup(enabled = true, lastBackupAt = 123L, lastBackupError = ""),
        )
        assertEquals(ProjectStatusLevel.GREEN, statuses.cloud.level)
        assertEquals("backup_ok", statuses.cloud.code)
    }
}

class GitStatusInspectorTest {

    @Test
    fun readsOriginRemoteFromGitConfig() {
        val dir = File(System.getProperty("java.io.tmpdir"), "git-inspector-${System.nanoTime()}").apply { mkdirs() }
        File(dir, ".git").mkdirs()
        File(dir, ".git/config").writeText(
            """
            [core]
                repositoryformatversion = 0
            [remote "origin"]
                url = https://github.com/owner/repo.git
                fetch = +refs/heads/*:refs/remotes/origin/*
            """.trimIndent()
        )
        assertEquals("https://github.com/owner/repo.git", GitStatusInspector.readOriginRemote(dir))
        dir.deleteRecursively()
    }

    @Test
    fun returnsNullWhenNoGit() {
        val dir = File(System.getProperty("java.io.tmpdir"), "git-inspector-empty-${System.nanoTime()}").apply { mkdirs() }
        assertNull(GitStatusInspector.readOriginRemote(dir))
        val status = GitStatusInspector.inspect(dir, runGit = false)
        assertEquals(false, status.hasGitDir)
        assertNull(status.remoteUrl)
        dir.deleteRecursively()
    }

    @Test
    fun inspectWithoutGitRunIsNotVerified() {
        val dir = File(System.getProperty("java.io.tmpdir"), "git-inspector-noverify-${System.nanoTime()}").apply { mkdirs() }
        File(dir, ".git").mkdirs()
        File(dir, ".git/config").writeText("[remote \"origin\"]\n\turl = https://github.com/o/r.git\n")
        val status = GitStatusInspector.inspect(dir, runGit = false)
        assertEquals(true, status.hasGitDir)
        assertEquals(false, status.checked)
        assertEquals("https://github.com/o/r.git", status.remoteUrl)
        dir.deleteRecursively()
    }
}
