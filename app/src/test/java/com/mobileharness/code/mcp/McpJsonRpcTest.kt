package com.mobileharness.code.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonRpcTest {

    @Test
    fun initializeRequestCarriesProtocolVersionAndClientInfo() {
        val json = McpJsonRpc.initializeRequest(1, "1.3.1")
        val root = JSONObject(json)
        assertEquals("2.0", root.getString("jsonrpc"))
        assertEquals(1L, root.getLong("id"))
        assertEquals("initialize", root.getString("method"))
        assertEquals("2024-11-05", root.getJSONObject("params").getString("protocolVersion"))
        assertEquals("mobile-harness-code", root.getJSONObject("params").getJSONObject("clientInfo").getString("name"))
    }

    @Test
    fun initializedNotificationHasNoId() {
        val root = JSONObject(McpJsonRpc.initializedNotification())
        assertEquals("notifications/initialized", root.getString("method"))
        assertFalse(root.has("id"))
    }

    @Test
    fun toolsListAndCallRequestsAreWellFormed() {
        val list = JSONObject(McpJsonRpc.toolsListRequest(2))
        assertEquals("tools/list", list.getString("method"))
        assertEquals(2L, list.getLong("id"))

        val call = JSONObject(McpJsonRpc.toolsCallRequest(3, "search", JSONObject().put("q", "x")))
        assertEquals("tools/call", call.getString("method"))
        assertEquals("search", call.getJSONObject("params").getString("name"))
        assertEquals("x", call.getJSONObject("params").getJSONObject("arguments").getString("q"))
    }

    @Test
    fun parseToolsListExtractsNamesDescriptionsAndSchemas() {
        val body = """{"jsonrpc":"2.0","id":2,"result":{"tools":[
            {"name":"search","description":"Search docs","inputSchema":{"type":"object"}},
            {"name":"read_file"}
        ]}}"""
        val tools = McpJsonRpc.parseToolsList(body)
        assertEquals(2, tools.size)
        assertEquals("search", tools[0].name)
        assertEquals("Search docs", tools[0].description)
        assertTrue(tools[0].inputSchema.contains("type"))
        assertEquals("read_file", tools[1].name)
        assertEquals("{}", tools[1].inputSchema)
    }

    @Test
    fun parseToolsListRejectsErrorsAndGarbage() {
        assertTrue(McpJsonRpc.parseToolsList("""{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"nope"}}""").isEmpty())
        assertTrue(McpJsonRpc.parseToolsList("not json").isEmpty())
        assertTrue(McpJsonRpc.parseToolsList("").isEmpty())
    }

    @Test
    fun parseToolsCallExtractsTextAndErrorFlag() {
        val body = """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"},{"type":"text","text":" world"}],"isError":false}}"""
        val out = McpJsonRpc.parseToolsCall(body)
        assertEquals("hello world", out.text)
        assertFalse(out.isError)
    }

    @Test
    fun parseToolsCallMapsRpcErrorToErrorOutput() {
        val body = """{"jsonrpc":"2.0","id":3,"error":{"code":-32000,"message":"tool blew up"}}"""
        val out = McpJsonRpc.parseToolsCall(body)
        assertTrue(out.isError)
        assertEquals("tool blew up", out.text)
    }

    @Test
    fun extractErrorReturnsMessageOnlyForErrorResponses() {
        assertNotNull(McpJsonRpc.extractError("""{"jsonrpc":"2.0","id":1,"error":{"code":1,"message":"bad"}}"""))
        assertEquals(null, McpJsonRpc.extractError("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
    }
}
