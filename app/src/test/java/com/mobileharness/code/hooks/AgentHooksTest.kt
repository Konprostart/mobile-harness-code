package com.mobileharness.code.hooks

import com.mobileharness.code.permission.DenialReason
import com.mobileharness.code.permission.PermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHooksTest {

    @Test
    fun dispatch_noRulesAllows() {
        val result = HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Bash", emptyList())
        assertEquals(HookAction.ALLOW, result.action)
    }

    @Test
    fun dispatch_mostRestrictiveActionWins() {
        val rules = listOf(
            HookRule("1", HookEvent.PRE_TOOL_USE, "*", HookAction.ALLOW),
            HookRule("2", HookEvent.PRE_TOOL_USE, "Bash", HookAction.BLOCK, "block bash"),
            HookRule("3", HookEvent.PRE_TOOL_USE, "Bash", HookAction.STOP),
        )
        val result = HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Bash", rules)
        assertEquals(HookAction.STOP, result.action)
        assertTrue(result.message.contains("block bash"))
    }

    @Test
    fun dispatch_matcherSupportsRegexAndLiteral() {
        val literal = listOf(HookRule("1", HookEvent.PRE_TOOL_USE, "Write", HookAction.BLOCK))
        assertEquals(HookAction.BLOCK, HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Write", literal).action)
        assertEquals(HookAction.ALLOW, HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Read", literal).action)

        val regex = listOf(HookRule("1", HookEvent.PRE_TOOL_USE, "Write|Edit", HookAction.BLOCK))
        assertEquals(HookAction.BLOCK, HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Edit", regex).action)
        assertEquals(HookAction.ALLOW, HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Read", regex).action)
    }

    @Test
    fun dispatch_disabledRulesAreIgnored() {
        val rules = listOf(HookRule("1", HookEvent.PRE_TOOL_USE, "*", HookAction.BLOCK, enabled = false))
        assertEquals(HookAction.ALLOW, HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Bash", rules).action)
    }

    @Test
    fun dispatch_destructiveOperationAlwaysAsks() {
        val rules = listOf(HookRule("1", HookEvent.PRE_TOOL_USE, "Bash", HookAction.ALLOW))
        val result = HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Bash", rules, HookEngine.Context(destructive = true))
        assertEquals(HookAction.ASK, result.action)
    }

    @Test
    fun dispatch_permissionDenialCannotBeDowngradedToAllow() {
        val rules = listOf(HookRule("1", HookEvent.PRE_TOOL_USE, "Bash", HookAction.ALLOW))
        val denied = PermissionDecision.Denied(DenialReason.DESTRUCTIVE_REQUIRES_ASK)
        val result = HookEngine.dispatch(HookEvent.PRE_TOOL_USE, "Bash", rules, HookEngine.Context(permissionDecision = denied))
        assertEquals(HookAction.BLOCK, result.action)
    }

    @Test
    fun eventFromExternal_mapsClaudeNames() {
        assertEquals(HookEvent.PRE_TOOL_USE, HookEvent.fromExternal("PreToolUse"))
        assertEquals(HookEvent.POST_TOOL_USE, HookEvent.fromExternal("PostToolUse"))
        assertEquals(HookEvent.POST_TOOL_USE_FAILURE, HookEvent.fromExternal("PostToolUseFailure"))
        assertEquals(HookEvent.PRE_COMPACT, HookEvent.fromExternal("PreCompact"))
        assertEquals(null, HookEvent.fromExternal("UnknownEvent"))
    }

    @Test
    fun hookAuditLog_isBounded() {
        val log = HookAuditLog(maxEntries = 3)
        repeat(5) { i ->
            log.record(HookAuditEntry(i.toLong(), HookEvent.PRE_TOOL_USE, "r", HookAction.ALLOW, "m"))
        }
        assertEquals(3, log.snapshot().size)
        log.clear()
        assertEquals(0, log.snapshot().size)
    }
}
