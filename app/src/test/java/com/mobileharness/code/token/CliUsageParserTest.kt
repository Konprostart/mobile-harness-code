package com.mobileharness.code.token

import com.mobileharness.code.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliUsageParserTest {

    @Test
    fun parsesClaudeResultUsage() {
        val json = JSONObject(
            """
            {
              "type": "result",
              "subtype": "success",
              "is_error": false,
              "duration_ms": 1234,
              "num_turns": 3,
              "result": "done",
              "session_id": "sess-123",
              "total_cost_usd": 0.0123,
              "usage": {
                "input_tokens": 500,
                "output_tokens": 100,
                "cache_creation_input_tokens": 50,
                "cache_read_input_tokens": 400
              },
              "modelUsage": {"claude-sonnet-4-6": {"inputTokens": 500, "outputTokens": 100}}
            }
            """.trimIndent(),
        )
        val parsed = CliUsageParser.parseClaudeResult(json)!!
        assertTrue(parsed.hasUsage)
        assertEquals(500L, parsed.usage.inputTokens)
        assertEquals(100L, parsed.usage.outputTokens)
        assertEquals(400L, parsed.usage.cachedReadTokens)
        assertEquals(50L, parsed.usage.cacheWriteTokens)
        assertEquals("claude-sonnet-4-6", parsed.model)
        assertEquals(0.0123, parsed.reportedCostUsd!!, 1e-9)
        assertEquals(1234L, parsed.durationMillis)
        assertEquals("sess-123", parsed.sessionId)
    }

    @Test
    fun claudeResultWithoutUsageButWithCostIsNotNull() {
        val json = JSONObject("""{"type":"result","total_cost_usd":0.5,"model":"claude-sonnet-4-6"}""")
        val parsed = CliUsageParser.parseClaudeResult(json)!!
        assertFalse(parsed.hasUsage)
        assertEquals(0.5, parsed.reportedCostUsd!!, 1e-9)
        assertEquals("claude-sonnet-4-6", parsed.model)
    }

    @Test
    fun claudeResultWithoutAnySignalIsNull() {
        val json = JSONObject("""{"type":"result","subtype":"success"}""")
        assertNull(CliUsageParser.parseClaudeResult(json))
    }

    @Test
    fun extractUsageFromGenericOpenAiShape() {
        val json = JSONObject(
            """{"usage":{"prompt_tokens":1000,"completion_tokens":200,"prompt_tokens_details":{"cached_tokens":300}}}""",
        )
        val usage = CliUsageParser.extractUsage(json, ProviderProtocol.OPENAI_CHAT)!!
        assertEquals(700L, usage.inputTokens)
        assertEquals(200L, usage.outputTokens)
        assertEquals(300L, usage.cachedReadTokens)
    }

    @Test
    fun extractUsageReturnsNullWhenAbsent() {
        assertNull(CliUsageParser.extractUsage(JSONObject("""{"result":"ok"}"""), ProviderProtocol.ANTHROPIC))
    }
}
