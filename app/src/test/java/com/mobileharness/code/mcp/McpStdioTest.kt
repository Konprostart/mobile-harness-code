package com.mobileharness.code.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStdioTest {

    @Test
    fun encodeAppendsNewline() {
        assertEquals("{\"jsonrpc\":\"2.0\"}\n", McpStdio.encode("{\"jsonrpc\":\"2.0\"}"))
    }

    @Test
    fun decodeLinesSplitsCompleteLinesAndKeepsRemainder() {
        val (lines, remainder) = McpStdio.decodeLines("{\"a\":1}\n{\"b\":2}\n{\"c\":3")
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), lines)
        assertEquals("{\"c\":3", remainder)
    }

    @Test
    fun decodeLinesWithNoTerminatorKeepsEverything() {
        val (lines, remainder) = McpStdio.decodeLines("{\"a\":1}")
        assertTrue(lines.isEmpty())
        assertEquals("{\"a\":1}", remainder)
    }

    @Test
    fun splitCommandSplitsOnWhitespaceWithoutAShell() {
        assertEquals(listOf("npx", "-y", "@scope/pkg", "/path", "with", "space"), McpStdio.splitCommand("  npx   -y  @scope/pkg  /path with space  "))
        assertEquals(emptyList<String>(), McpStdio.splitCommand("   "))
    }
}
