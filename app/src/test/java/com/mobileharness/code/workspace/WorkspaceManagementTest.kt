package com.mobileharness.code.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceManagementTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun grants_effectivePermissionIsMostSpecific() {
        val global = WorkspaceGrant("g1", "claude", WorkspacePermission.READ)
        val project = WorkspaceGrant("g2", "claude", WorkspacePermission.WRITE, projectId = "p1")
        val grants = listOf(global, project)
        assertEquals(WorkspacePermission.WRITE, WorkspaceGrants.effective("claude", null, "p1", grants))
        assertEquals(WorkspacePermission.READ, WorkspaceGrants.effective("claude", null, "p2", grants))
        assertTrue(WorkspaceGrants.can("claude", null, "p1", WorkspacePermission.READ, grants))
        assertTrue(WorkspaceGrants.can("claude", null, "p1", WorkspacePermission.WRITE, grants))
        assertFalse(WorkspaceGrants.can("claude", null, "p1", WorkspacePermission.ADMIN, grants))
        assertFalse(WorkspaceGrants.can("deepseek", null, "p1", WorkspacePermission.READ, grants))
    }

    @Test
    fun isolation_rejectsTraversalAndEscape() {
        val root = tmp.newFolder("ws")
        assertTrue(WorkspaceIsolation.resolve(root, "src/Main.kt") is WorkspaceIsolation.Verdict.Ok)
        assertTrue(WorkspaceIsolation.resolve(root, "../secret.txt") is WorkspaceIsolation.Verdict.Denied)
        assertTrue(WorkspaceIsolation.resolve(root, "/etc/passwd") is WorkspaceIsolation.Verdict.Denied)
        assertTrue(WorkspaceIsolation.resolve(root, "") is WorkspaceIsolation.Verdict.Denied)
    }

    @Test
    fun isolation_blocksSymlinkEscape() {
        val root = tmp.newFolder("ws")
        val outside = tmp.newFile("outside.txt").apply { writeText("secret") }
        java.nio.file.Files.createSymbolicLink(root.toPath().resolve("link.txt"), outside.toPath())
        val verdict = WorkspaceIsolation.resolve(root, "link.txt")
        assertTrue(verdict is WorkspaceIsolation.Verdict.Denied)
    }

    @Test
    fun lockManager_exclusiveOwner() {
        val locks = WorkspaceLockManager()
        assertTrue(locks.acquire("ws1", "claude"))
        assertFalse(locks.acquire("ws1", "deepseek"))
        assertEquals("claude", locks.owner("ws1"))
        locks.release("ws1", "claude")
        assertTrue(locks.acquire("ws1", "deepseek"))
        locks.releaseAll("deepseek")
        assertFalse(locks.isLocked("ws1"))
    }

    @Test
    fun manager_readOnlyWorkspaceBlocksWrites() {
        val root = tmp.newFolder("ro")
        val group = WorkspaceGroup(
            id = "g1",
            projectId = "p1",
            name = "My project",
            workspaces = listOf(WorkspaceRef(name = "main", path = root.path, role = WorkspaceRole.READ_ONLY)),
        )
        val manager = WorkspaceManager()
        manager.addGroup(group)
        manager.addGrant(WorkspaceGrant("g1", "claude", WorkspacePermission.WRITE, workspaceId = "main", projectId = "p1"))
        val ws = manager.group("g1")!!.workspaces.first()
        // Even with a WRITE grant, a read-only workspace refuses writes.
        val write = manager.resolvePath(ws, "file.txt", WorkspacePermission.WRITE)
        assertTrue(write is WorkspaceIsolation.Verdict.Denied)
        // Reads are allowed.
        val read = manager.resolvePath(ws, "file.txt", WorkspacePermission.READ)
        assertTrue(read is WorkspaceIsolation.Verdict.Ok)
    }

    @Test
    fun auditLog_isBounded() {
        val log = WorkspaceAuditLog(maxEntries = 2)
        repeat(4) { i ->
            log.record(WorkspaceAuditEntry(i.toLong(), "a", "ws", WorkspacePermission.READ, null, true, "m"))
        }
        assertEquals(2, log.snapshot().size)
        log.clear()
        assertEquals(0, log.snapshot().size)
    }
}
