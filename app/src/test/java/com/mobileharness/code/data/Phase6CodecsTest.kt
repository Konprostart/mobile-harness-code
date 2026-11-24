package com.mobileharness.code.data

import com.mobileharness.code.hooks.HookAction
import com.mobileharness.code.hooks.HookEvent
import com.mobileharness.code.hooks.HookRule
import com.mobileharness.code.lsp.LspServerDefinition
import com.mobileharness.code.testing.BuildSystem
import com.mobileharness.code.testing.TestHistoryEntry
import com.mobileharness.code.workspace.WorkspaceGrant
import com.mobileharness.code.workspace.WorkspaceGroup
import com.mobileharness.code.workspace.WorkspacePermission
import com.mobileharness.code.workspace.WorkspaceRef
import com.mobileharness.code.workspace.WorkspaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6CodecsTest {

    @Test
    fun hookRules_roundTrip() {
        val rules = listOf(
            HookRule("r1", HookEvent.PRE_TOOL_USE, "Bash", HookAction.BLOCK, "block bash"),
            HookRule("r2", HookEvent.SESSION_START, "*", HookAction.ALLOW),
        )
        val decoded = HookRuleCodec.fromJson(HookRuleCodec.toJson(rules))
        assertEquals(rules, decoded)
    }

    @Test
    fun hookRules_malformedJsonIsDropped() {
        assertEquals(emptyList<HookRule>(), HookRuleCodec.fromJson("{not json"))
        assertEquals(emptyList<HookRule>(), HookRuleCodec.fromJson(null))
    }

    @Test
    fun workspaceGroupsAndGrants_roundTrip() {
        val groups = listOf(
            WorkspaceGroup("g1", "p1", "Project", listOf(WorkspaceRef(name = "main", path = "/w", role = WorkspaceRole.MAIN), WorkspaceRef(name = "ro", path = "/w2", role = WorkspaceRole.READ_ONLY))),
        )
        val grants = listOf(
            WorkspaceGrant("gr1", "claude", WorkspacePermission.WRITE, workspaceId = "main", projectId = "p1"),
            WorkspaceGrant("gr2", "sidekick", WorkspacePermission.READ, projectId = "p1"),
        )
        assertEquals(groups, WorkspaceManagementCodec.groupsFromJson(WorkspaceManagementCodec.groupsToJson(groups)))
        assertEquals(grants, WorkspaceManagementCodec.grantsFromJson(WorkspaceManagementCodec.grantsToJson(grants)))
    }

    @Test
    fun lspServers_roundTrip() {
        val servers = listOf(
            LspServerDefinition(name = "kotlin", command = "kotlin-lsp", args = listOf("--stdio"), env = mapOf("K" to "V"), extensions = mapOf(".kt" to "kotlin"), languageIds = listOf("kotlin"), license = "Apache-2.0"),
        )
        val decoded = LspServerCodec.fromJson(LspServerCodec.toJson(servers))
        assertEquals(servers, decoded)
    }

    @Test
    fun testingHistory_roundTrip() {
        val entries = listOf(TestHistoryEntry(1L, BuildSystem.GRADLE, 3, 3, 0, 0, "success"))
        assertEquals(entries, TestingHistoryCodec.fromJson(TestingHistoryCodec.toJson(entries)))
    }

    @Test
    fun codecs_tolerateEmptyAndMalformed() {
        assertTrue(WorkspaceManagementCodec.groupsFromJson(null).isEmpty())
        assertTrue(WorkspaceManagementCodec.grantsFromJson("").isEmpty())
        assertTrue(LspServerCodec.fromJson("[]").isEmpty())
        assertTrue(TestingHistoryCodec.fromJson("garbage").isEmpty())
    }
}
