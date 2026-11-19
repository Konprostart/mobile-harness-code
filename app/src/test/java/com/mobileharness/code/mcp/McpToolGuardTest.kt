package com.mobileharness.code.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolGuardTest {

    private fun server(mode: McpToolConfirmMode) = McpServerConfig(
        id = "s1",
        name = "test",
        transport = McpTransport.HTTP,
        url = "https://mcp.example.com",
        confirmMode = mode,
    )

    private val readOnly = McpTool("search", "Search the documentation", "{}")
    private val mutating = McpTool("delete_file", "Delete a file", "{}")

    @Test
    fun mutatingOnlyApprovesReadOnlyTools() {
        val decision = McpToolGuard.decide(readOnly, server(McpToolConfirmMode.MUTATING_ONLY), "{}", 16_000)
        assertTrue(decision is McpToolGuard.Decision.Approved)
    }

    @Test
    fun mutatingOnlyHoldsMutatingToolsForConfirmation() {
        val decision = McpToolGuard.decide(mutating, server(McpToolConfirmMode.MUTATING_ONLY), "{}", 16_000)
        assertTrue(decision is McpToolGuard.Decision.NeedsConfirmation)
    }

    @Test
    fun alwaysConfirmHoldsEverything() {
        assertTrue(McpToolGuard.decide(readOnly, server(McpToolConfirmMode.ALWAYS_CONFIRM), "{}", 16_000) is McpToolGuard.Decision.NeedsConfirmation)
        assertTrue(McpToolGuard.decide(mutating, server(McpToolConfirmMode.ALWAYS_CONFIRM), "{}", 16_000) is McpToolGuard.Decision.NeedsConfirmation)
    }

    @Test
    fun neverLetsEverythingThrough() {
        assertTrue(McpToolGuard.decide(readOnly, server(McpToolConfirmMode.NEVER), "{}", 16_000) is McpToolGuard.Decision.Approved)
        assertTrue(McpToolGuard.decide(mutating, server(McpToolConfirmMode.NEVER), "{}", 16_000) is McpToolGuard.Decision.Approved)
    }

    @Test
    fun oversizedInputIsAlwaysBlocked() {
        val big = "{\"q\":\"${"x".repeat(100)}\"}"
        assertTrue(McpToolGuard.decide(readOnly, server(McpToolConfirmMode.NEVER), big, 50) is McpToolGuard.Decision.Blocked)
    }
}
