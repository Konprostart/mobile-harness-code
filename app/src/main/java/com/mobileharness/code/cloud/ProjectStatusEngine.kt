package com.mobileharness.code.cloud

import com.mobileharness.code.model.Project

enum class ProjectStatusLevel { GREEN, RED, UNKNOWN }

data class ProjectStatus(val level: ProjectStatusLevel, val code: String)

data class ProjectStatuses(
    val github: ProjectStatus,
    val signiffia: ProjectStatus,
    val cloud: ProjectStatus,
)

/**
 * Computes the three per-project status lamps from real, inspectable inputs (never from a toggle).
 *
 *  - GitHub:      green only when a remote exists AND a real git run confirmed no unsynced changes;
 *                 red when not connected or unsynced; neutral when the check could not be performed.
 *  - Signiffia:   green only when every bound key id is present in the vault (valid fingerprints are
 *                 enforced by the vault itself); red when no key is bound or a bound key is missing.
 *  - Cloud:       green only after a successful backup (no error, timestamp set); red when not
 *                 configured, never backed up, or the last backup failed.
 */
object ProjectStatusEngine {

    fun compute(
        project: Project,
        git: GitProjectStatus,
        vaultKeyIds: Set<String>,
        cloud: ProjectCloudBackup,
    ): ProjectStatuses = ProjectStatuses(
        github = githubStatus(project, git),
        signiffia = signiffiaStatus(project, vaultKeyIds),
        cloud = cloudStatus(cloud),
    )

    fun githubStatus(project: Project, git: GitProjectStatus): ProjectStatus {
        val remote = project.gitRemoteUrl ?: git.remoteUrl
        return when {
            remote == null -> ProjectStatus(ProjectStatusLevel.RED, "not_connected")
            git.error != null -> ProjectStatus(ProjectStatusLevel.UNKNOWN, "check_failed")
            !git.checked -> ProjectStatus(ProjectStatusLevel.UNKNOWN, "not_verified")
            git.unsynced -> ProjectStatus(ProjectStatusLevel.RED, "unsynced")
            else -> ProjectStatus(ProjectStatusLevel.GREEN, "synced")
        }
    }

    fun signiffiaStatus(project: Project, vaultKeyIds: Set<String>): ProjectStatus {
        val bound = project.signingKeyIds
        return when {
            bound.isEmpty() -> ProjectStatus(ProjectStatusLevel.RED, "no_key")
            bound.any { it !in vaultKeyIds } -> ProjectStatus(ProjectStatusLevel.RED, "missing_key")
            else -> ProjectStatus(ProjectStatusLevel.GREEN, "keys_saved")
        }
    }

    fun cloudStatus(cloud: ProjectCloudBackup): ProjectStatus = when {
        !cloud.enabled -> ProjectStatus(ProjectStatusLevel.RED, "not_configured")
        cloud.lastBackupError.isNotBlank() -> ProjectStatus(ProjectStatusLevel.RED, "backup_failed")
        cloud.lastBackupAt <= 0L -> ProjectStatus(ProjectStatusLevel.RED, "no_backup")
        else -> ProjectStatus(ProjectStatusLevel.GREEN, "backup_ok")
    }
}
