package com.mobileharness.code.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPolicyTest {

    @Test
    fun httpsIsAlwaysAllowed() {
        assertTrue(McpPolicy.isAllowedHttpUrl("https://mcp.example.com"))
        assertTrue(McpPolicy.isAllowedHttpUrl("https://server.internal/mcp"))
    }

    @Test
    fun plainHttpAllowedOnlyForLocalOrPrivateHosts() {
        assertTrue(McpPolicy.isAllowedHttpUrl("http://127.0.0.1:8080"))
        assertTrue(McpPolicy.isAllowedHttpUrl("http://localhost"))
        assertTrue(McpPolicy.isAllowedHttpUrl("http://192.168.1.10/mcp"))
        assertTrue(McpPolicy.isAllowedHttpUrl("http://10.0.0.5"))
        assertTrue(McpPolicy.isAllowedHttpUrl("http://[::1]:8080"))
        assertTrue(McpPolicy.isAllowedHttpUrl("http://tool.local"))
        assertTrue(McpPolicy.isAllowedHttpUrl("http://singlelabel"))
    }

    @Test
    fun plainHttpToPublicHostsIsRejected() {
        assertFalse(McpPolicy.isAllowedHttpUrl("http://evil.example.com"))
        assertFalse(McpPolicy.isAllowedHttpUrl("http://8.8.8.8"))
        assertFalse(McpPolicy.isAllowedHttpUrl("http://api.openai.com"))
    }

    @Test
    fun nonHttpSchemesAndBlankAreRejected() {
        assertFalse(McpPolicy.isAllowedHttpUrl("ftp://127.0.0.1"))
        assertFalse(McpPolicy.isAllowedHttpUrl("file:///tmp"))
        assertFalse(McpPolicy.isAllowedHttpUrl(""))
        assertFalse(McpPolicy.isAllowedHttpUrl("not a url"))
    }

    @Test
    fun mutatingMarkersAreDetectedConservatively() {
        assertTrue(McpPolicy.isMutating("delete_file", "Delete a file"))
        assertTrue(McpPolicy.isMutating("write_notes", "Writes a note"))
        assertTrue(McpPolicy.isMutating("run_command", "Execute a shell command"))
        assertTrue(McpPolicy.isMutating("update", "Updates the record"))
    }

    @Test
    fun readOnlyToolsAreNotMutating() {
        assertFalse(McpPolicy.isMutating("search", "Search the docs"))
        assertFalse(McpPolicy.isMutating("list_files", "List directory entries"))
        assertFalse(McpPolicy.isMutating("read_file", "Read a file"))
    }

    @Test
    fun ecoModeShrinksLimits() {
        assertTrue(McpPolicy.maxInputChars(true) < McpPolicy.maxInputChars(false))
        assertTrue(McpPolicy.maxOutputChars(true) < McpPolicy.maxOutputChars(false))
        assertTrue(McpPolicy.timeoutMillis(true) < McpPolicy.timeoutMillis(false))
    }

    @Test
    fun truncateIsBoundedAndAddsEllipsis() {
        assertEquals("abc…", McpPolicy.truncate("abcdefgh", 4))
        assertEquals("abcdefgh", McpPolicy.truncate("abcdefgh", 8))
        assertEquals("", McpPolicy.truncate("abcdefgh", 0))
    }

    @Test
    fun classifyHttpStatusDistinguishesAuthTimeoutAndNetwork() {
        assertEquals(McpCallFailureKind.AUTH, McpPolicy.classifyHttpStatus(401))
        assertEquals(McpCallFailureKind.AUTH, McpPolicy.classifyHttpStatus(403))
        assertEquals(McpCallFailureKind.TIMEOUT, McpPolicy.classifyHttpStatus(408))
        assertEquals(McpCallFailureKind.NETWORK, McpPolicy.classifyHttpStatus(500))
        assertEquals(McpCallFailureKind.NETWORK, McpPolicy.classifyHttpStatus(502))
        assertEquals(McpCallFailureKind.NETWORK, McpPolicy.classifyHttpStatus(429))
    }
}
