package com.mobileharness.code.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerCodecTest {

    @Test
    fun roundTripsAllFields() {
        val servers = listOf(
            McpServerConfig(
                id = "a",
                name = "Files",
                transport = McpTransport.STDIO,
                command = "npx -y server /sdcard",
                bearerToken = "secret",
                confirmMode = McpToolConfirmMode.ALWAYS_CONFIRM,
                enabled = false,
            ),
            McpServerConfig(
                id = "b",
                name = "Search",
                transport = McpTransport.HTTP,
                url = "https://mcp.example.com",
                confirmMode = McpToolConfirmMode.MUTATING_ONLY,
                enabled = true,
            ),
        )
        val json = McpServerCodec.toJson(servers)
        val decoded = McpServerCodec.fromJson(json)
        assertEquals(servers, decoded)
    }

    @Test
    fun emptyAndBlankProduceEmptyList() {
        assertTrue(McpServerCodec.fromJson("").isEmpty())
        assertTrue(McpServerCodec.fromJson("   ").isEmpty())
        assertEquals("[]", McpServerCodec.toJson(emptyList()))
    }

    @Test
    fun malformedAndUnknownEnumsAreTolerated() {
        val json = """[
            {"id":"ok","name":"good","transport":"HTTP"},
            {"id":"missing","transport":"BOGUS"},
            {"transport":"HTTP"}
        ]"""
        val decoded = McpServerCodec.fromJson(json)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].id)
        assertEquals(McpTransport.HTTP, decoded[0].transport)
        assertEquals(McpToolConfirmMode.MUTATING_ONLY, decoded[0].confirmMode)
    }

    @Test
    fun notJsonYieldsEmptyList() {
        assertTrue(McpServerCodec.fromJson("this is not json").isEmpty())
    }
}
