package com.mobileharness.code.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class McpToolCallParserTest {

    @Test
    fun parsesFencedToolBlock() {
        val text = "I will search.\n```tool\n{\"tool\":\"search\",\"arguments\":{\"query\":\"android\"}}\n```\n"
        val request = McpToolCallParser.parse(text)
        assertNotNull(request)
        assertEquals("search", request!!.toolName)
        assertEquals("android", request.arguments.getString("query"))
    }

    @Test
    fun parsesFencedJsonBlock() {
        val request = McpToolCallParser.parse("```json\n{\"tool\":\"read_file\",\"arguments\":{\"path\":\"/a\"}}\n```")
        assertNotNull(request)
        assertEquals("read_file", request!!.toolName)
    }

    @Test
    fun parsesBareLeadingJsonObject() {
        val request = McpToolCallParser.parse("{\"tool\":\"search\",\"arguments\":{}}")
        assertNotNull(request)
        assertEquals("search", request!!.toolName)
    }

    @Test
    fun returnsNullForPlainText() {
        assertNull(McpToolCallParser.parse("Here is a normal answer without any tool call."))
    }

    @Test
    fun returnsNullForJsonWithoutToolMember() {
        assertNull(McpToolCallParser.parse("{\"hello\":\"world\"}"))
    }
}
