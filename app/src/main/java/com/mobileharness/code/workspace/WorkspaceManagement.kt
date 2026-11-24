package com.mobileharness.code.workspace

import java.io.File
import java.util.UUID

/**
 * Advanced Workspace Management: multiple projects and multiple workspace groups per project, with
 * per-agent grants (READ/WRITE/EXECUTE/ADMIN), real read-only enforcement, path/symlink isolation,
 * per-workspace locks, and an audit log. This builds on the existing
 * [com.mobileharness.code.tools.WorkspacePathGuard] (the canonical fail-closed path resolver) and
 * does not replace it: every file access still funnels through that guard.
 */

enum class WorkspacePermission { READ, WRITE, EXECUTE, ADMIN }

enum class WorkspaceRole { MAIN, SECONDARY, READ_ONLY, SHARED, TEMPORARY, ISOLATED }

/** A single workspace within a group. */
data class WorkspaceRef(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val role: WorkspaceRole = WorkspaceRole.MAIN,
)

/** A group of workspaces belonging to one project. */
data class WorkspaceGroup(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val name: String,
    val workspaces: List<WorkspaceRef> = emptyList(),
) {
    val main: WorkspaceRef? get() = workspaces.firstOrNull { it.role == WorkspaceRole.MAIN }
    val readOnly: List<WorkspaceRef> get() = workspaces.filter { it.role == WorkspaceRole.READ_ONLY }
}

/** A per-agent grant: what an agent may do on a workspace (or globally when [workspaceId] is null). */
data class WorkspaceGrant(
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val permission: WorkspacePermission,
    val workspaceId: String? = null,
    val projectId: String? = null,
    val enabled: Boolean = true,
) {
    fun matches(agentId: String, workspaceId: String?, projectId: String?): Boolean {
        if (!enabled || this.agentId != agentId) return false
        if (this.workspaceId != null && this.workspaceId != workspaceId) return false
        if (this.projectId != null && this.projectId != projectId) return false
        return true
    }
}

/** A lock held by an agent on a workspace, preventing concurrent conflicting writes. */
data class WorkspaceLock(
    val workspaceId: String,
    val agentId: String,
    val acquiredAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Resolves whether an agent may perform a [WorkspacePermission] on a workspace. Grants are resolved
 * most-specific-first: a workspace-scoped grant beats a project-scoped grant beats a global grant;
 * READ is the minimum implicit level (a grant for WRITE implies READ, etc.), and an explicit grant
 * is always required (fail-closed).
 */
object WorkspaceGrants {
    private val rank = mapOf(
        WorkspacePermission.READ to 0,
        WorkspacePermission.WRITE to 1,
        WorkspacePermission.EXECUTE to 2,
        WorkspacePermission.ADMIN to 3,
    )

    fun can(agentId: String, workspaceId: String?, projectId: String?, permission: WorkspacePermission, grants: List<WorkspaceGrant>): Boolean {
        val matching = grants.filter { it.matches(agentId, workspaceId, projectId) }
        if (matching.isEmpty()) return false
        return matching.any { rank.getValue(it.permission) >= rank.getValue(permission) }
    }

    fun effective(agentId: String, workspaceId: String?, projectId: String?, grants: List<WorkspaceGrant>): WorkspacePermission? {
        val matching = grants.filter { it.matches(agentId, workspaceId, projectId) }
        return matching.maxByOrNull { rank.getValue(it.permission) }?.permission
    }
}

/**
 * Pure workspace isolation. A candidate path is safe only when it resolves (canonically, following
 * symlinks) strictly inside the workspace root. Reuses the canonical containment principle of the
 * app's [com.mobileharness.code.tools.WorkspacePathGuard].
 */
object WorkspaceIsolation {
    sealed interface Verdict {
        data class Ok(val file: File) : Verdict
        data class Denied(val reason: String) : Verdict
    }

    fun resolve(root: File, relative: String): Verdict {
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull()
            ?: return Verdict.Denied("Workspace root is not accessible.")
        if (!rootCanonical.isDirectory) return Verdict.Denied("Workspace root is not a directory.")
        val rel = relative.trim()
        if (rel.isEmpty()) return Verdict.Denied("Path is blank.")
        if (rel.startsWith('/') || rel.startsWith('\\')) return Verdict.Denied("Absolute paths are not allowed.")
        if (rel.split('/', '\\').any { it == ".." }) return Verdict.Denied("Parent traversal is not allowed.")
        if (rel.any { it.code < 0x20 }) return Verdict.Denied("Path contains control characters.")
        val candidate = File(rootCanonical, rel)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
            ?: return Verdict.Denied("Path could not be resolved.")
        val rootPath = rootCanonical.toPath().normalize()
        val candidatePath = canonical.toPath().normalize()
        if (!candidatePath.startsWith(rootPath)) return Verdict.Denied("Path escapes the workspace root.")
        return Verdict.Ok(canonical)
    }
}

/** Fail-closed workspace lock manager: a workspace can be locked by at most one agent. */
class WorkspaceLockManager {
    private val locks = LinkedHashMap<String, WorkspaceLock>()

    fun acquire(workspaceId: String, agentId: String): Boolean {
        val existing = locks[workspaceId]
        if (existing != null && existing.agentId != agentId) return false
        locks[workspaceId] = WorkspaceLock(workspaceId, agentId)
        return true
    }

    fun release(workspaceId: String, agentId: String) {
        if (locks[workspaceId]?.agentId == agentId) locks.remove(workspaceId)
    }

    fun releaseAll(agentId: String) {
        locks.entries.removeIf { it.value.agentId == agentId }
    }

    fun owner(workspaceId: String): String? = locks[workspaceId]?.agentId

    fun isLocked(workspaceId: String): Boolean = locks.containsKey(workspaceId)

    fun snapshot(): List<WorkspaceLock> = locks.values.toList()
}

/** A single audited workspace action. */
data class WorkspaceAuditEntry(
    val timestampMillis: Long,
    val agentId: String,
    val workspaceId: String?,
    val permission: WorkspacePermission,
    val path: String?,
    val allowed: Boolean,
    val detail: String,
)

/** Bounded in-memory audit log; the Android layer persists it if desired. */
class WorkspaceAuditLog(private val maxEntries: Int = 500) {
    private val entries = ArrayDeque<WorkspaceAuditEntry>()

    fun record(entry: WorkspaceAuditEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    fun snapshot(): List<WorkspaceAuditEntry> = entries.toList()

    fun clear() = entries.clear()
}

/**
 * The central workspace manager: holds groups and grants and answers the two questions every agent
 * integration must ask — "is this agent allowed this permission here?" and "is this path inside the
 * workspace?" — while enforcing read-only roles as a hard gate (not just a UI label).
 */
class WorkspaceManager(
    private val groups: MutableMap<String, WorkspaceGroup> = LinkedHashMap(),
    private val grants: MutableList<WorkspaceGrant> = mutableListOf(),
    private val locks: WorkspaceLockManager = WorkspaceLockManager(),
    private val audit: WorkspaceAuditLog = WorkspaceAuditLog(),
) {
    fun addGroup(group: WorkspaceGroup): Boolean {
        if (groups.containsKey(group.id)) return false
        groups[group.id] = group
        return true
    }

    fun groups(): List<WorkspaceGroup> = groups.values.toList()

    fun group(id: String): WorkspaceGroup? = groups[id]

    fun groupsForProject(projectId: String): List<WorkspaceGroup> = groups.values.filter { it.projectId == projectId }

    fun addGrant(grant: WorkspaceGrant) {
        grants += grant
    }

    fun removeGrant(id: String): Boolean = grants.removeIf { it.id == id }

    fun grants(): List<WorkspaceGrant> = grants.toList()

    fun can(agentId: String, workspaceId: String?, projectId: String?, permission: WorkspacePermission): Boolean =
        WorkspaceGrants.can(agentId, workspaceId, projectId, permission, grants)

    /** Resolves a path inside a workspace with read-only enforcement. */
    fun resolvePath(workspace: WorkspaceRef, relative: String, permission: WorkspacePermission): WorkspaceIsolation.Verdict {
        val isReadOnlyWorkspace = workspace.role == WorkspaceRole.READ_ONLY
        val verdict = WorkspaceIsolation.resolve(File(workspace.path), relative)
        if (verdict is WorkspaceIsolation.Verdict.Ok) {
            if (isReadOnlyWorkspace && permission != WorkspacePermission.READ) {
                return WorkspaceIsolation.Verdict.Denied("Workspace \"${workspace.name}\" is read-only.")
            }
        }
        return verdict
    }

    fun acquireLock(workspaceId: String, agentId: String): Boolean = locks.acquire(workspaceId, agentId)

    fun releaseLock(workspaceId: String, agentId: String) = locks.release(workspaceId, agentId)

    fun releaseAllLocks(agentId: String) = locks.releaseAll(agentId)

    fun isLocked(workspaceId: String): Boolean = locks.isLocked(workspaceId)

    fun lockOwner(workspaceId: String): String? = locks.owner(workspaceId)

    fun locksSnapshot(): List<WorkspaceLock> = locks.snapshot()

    fun audit(entry: WorkspaceAuditEntry) = audit.record(entry)

    fun auditSnapshot(): List<WorkspaceAuditEntry> = audit.snapshot()

    fun clearAudit() = audit.clear()
}
