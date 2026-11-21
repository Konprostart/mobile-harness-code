package com.mobileharness.code.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {

    private val request = PermissionRequest(
        subject = PermissionSubject.TOOL,
        subjectId = "Bash",
        operation = "run a build",
        projectId = "p1",
        workspacePath = "/workspace/p1",
        mutating = true,
    )

    @Test
    fun `blank subjectId is invalid`() {
        val d = PermissionPolicy.decide(request.copy(subjectId = " "), emptyList())
        assertTrue(d is PermissionDecision.Denied && (d as PermissionDecision.Denied).reason == DenialReason.INVALID_REQUEST)
    }

    @Test
    fun `emergency stop denies everything`() {
        val d = PermissionPolicy.decide(
            request,
            listOf(PermissionRule("r", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL)),
            PermissionPolicy.Context(emergencyStopped = true),
        )
        assertTrue(d is PermissionDecision.Denied && (d as PermissionDecision.Denied).reason == DenialReason.EMERGENCY_STOPPED)
    }

    @Test
    fun `secret access never auto-approves`() {
        val d = PermissionPolicy.decide(
            request.copy(touchesSecret = true),
            listOf(PermissionRule("r", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL)),
        )
        assertTrue(d is PermissionDecision.NeedsApproval)
    }

    @Test
    fun `destructive never auto-approves even under allow all`() {
        val d = PermissionPolicy.decide(
            request.copy(destructive = true),
            listOf(PermissionRule("r", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL)),
        )
        assertTrue(d is PermissionDecision.NeedsApproval)
    }

    @Test
    fun `out-of-workspace path is denied`() {
        val d = PermissionPolicy.decide(
            request.copy(targetPath = "/etc/passwd"),
            listOf(PermissionRule("r", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL)),
            PermissionPolicy.Context(workspaceRoots = setOf("/workspace/p1")),
        )
        assertTrue(d is PermissionDecision.Denied && (d as PermissionDecision.Denied).reason == DenialReason.OUTSIDE_WORKSPACE)
    }

    @Test
    fun `untrusted destination is denied`() {
        val d = PermissionPolicy.decide(
            request.copy(destinationHost = "evil.example.com"),
            listOf(PermissionRule("r", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL)),
            PermissionPolicy.Context(trustedHosts = setOf("api.openai.com")),
        )
        assertTrue(d is PermissionDecision.Denied && (d as PermissionDecision.Denied).reason == DenialReason.UNTRUSTED_DESTINATION)
    }

    @Test
    fun `no rule fails closed to needs approval`() {
        val d = PermissionPolicy.decide(request, emptyList())
        assertTrue(d is PermissionDecision.NeedsApproval)
    }

    @Test
    fun `explicit deny wins over allow`() {
        val d = PermissionPolicy.decide(
            request,
            listOf(
                PermissionRule("deny", PermissionSubject.TOOL, "Bash", PermissionLevel.DENY),
                PermissionRule("allow", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL),
            ),
        )
        assertTrue(d is PermissionDecision.Denied && (d as PermissionDecision.Denied).reason == DenialReason.EXPLICIT_DENY)
    }

    @Test
    fun `allow all grants autonomy for non-destructive in-scope action`() {
        val d = PermissionPolicy.decide(
            request,
            listOf(PermissionRule("r", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL, projectId = "p1")),
        )
        assertTrue(d is PermissionDecision.Allowed)
    }

    @Test
    fun `ask every time surfaces approval`() {
        val d = PermissionPolicy.decide(
            request,
            listOf(PermissionRule("r", PermissionSubject.TOOL, "Bash", PermissionLevel.ASK_EVERY_TIME)),
        )
        assertTrue(d is PermissionDecision.NeedsApproval)
    }

    @Test
    fun `more specific rule wins over global`() {
        val global = PermissionRule("g", PermissionSubject.TOOL, "Bash", PermissionLevel.ASK_EVERY_TIME)
        val project = PermissionRule("p", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ALL, projectId = "p1")
        val d = PermissionPolicy.decide(request, listOf(global, project))
        assertEquals("p", (d as PermissionDecision.Allowed).ruleId)
    }
}
