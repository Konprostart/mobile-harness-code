package com.mobileharness.code.network

import com.mobileharness.code.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesStructuredInputWithoutOutputCap() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Hello, reply with 1 word.", content.getString("text"))
        assertEquals(false, body.has("max_output_tokens"))
    }

    @Test
    fun openRouterProbeIncludesRequestedProviderOrder() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "anthropic/claude-sonnet-4.6",
                protocol = ProviderProtocol.OPENROUTER,
                openRouterProviderOrder = " anthropic, amazon-bedrock, anthropic ",
                openRouterAllowFallbacks = false,
            ),
        )

        val routing = body.getJSONObject("provider")
        assertEquals(listOf("anthropic", "amazon-bedrock"), routing.getJSONArray("order").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(routing.getBoolean("allow_fallbacks"))
        assertEquals(8, body.getInt("max_tokens"))
    }

    @Test
    fun chatAndAnthropicCompatibleProbesUseProviderSafeTokenLimit() {
        val client = ProviderApiClient()
        val chat = JSONObject(client.validationBody("chat-model", ProviderProtocol.OPENAI_CHAT))
        val anthropic = JSONObject(client.validationBody("claude-model", ProviderProtocol.ANTHROPIC))

        assertEquals(8, chat.getInt("max_tokens"))
        assertEquals(8, anthropic.getInt("max_tokens"))
    }

    @Test
    fun anthropicCompletionPutsSystemOutsideMessages() {
        val body = JSONObject(
            ProviderApiClient().completionBody(
                model = "claude-sonnet-4-6",
                protocol = ProviderProtocol.ANTHROPIC,
                systemPrompt = "be brief",
                userMessage = "hi",
            ),
        )

        assertEquals("claude-sonnet-4-6", body.getString("model"))
        assertEquals("be brief", body.getString("system"))
        assertFalse(body.has("instructions"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("hi", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun deepSeekReasoningContentIsExtractedFromOpenAiChat() {
        val body = """{"choices":[{"message":{"content":"final answer","reasoning_content":"step one, step two"}}]}"""
        val payload = ProviderApiClient().extractCompletion(body, ProviderProtocol.OPENAI_CHAT)

        assertEquals("final answer", payload.text)
        assertEquals("step one, step two", payload.reasoning)
    }

    @Test
    fun anthropicThinkingBlocksBecomeReasoning() {
        val body = """{"content":[{"type":"thinking","thinking":"let me think"},{"type":"text","text":"done"}]}"""
        val payload = ProviderApiClient().extractCompletion(body, ProviderProtocol.ANTHROPIC)

        assertEquals("done", payload.text)
        assertEquals("let me think", payload.reasoning)
    }

    @Test
    fun openAiResponsesReasoningAndTextAreSeparated() {
        val body = """{"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"rationale"}]},{"type":"message","content":[{"type":"output_text","text":"conclusion"}]}]}"""
        val payload = ProviderApiClient().extractCompletion(body, ProviderProtocol.OPENAI_RESPONSES)

        assertEquals("conclusion", payload.text)
        assertEquals("rationale", payload.reasoning)
    }

    @Test
    fun plainAnswerHasNoReasoning() {
        val body = """{"choices":[{"message":{"content":"just the answer"}}]}"""
        val payload = ProviderApiClient().extractCompletion(body, ProviderProtocol.OPENAI_CHAT)

        assertEquals("just the answer", payload.text)
        assertNull(payload.reasoning)
    }

    @Test
    fun buildAuthHeadersFollowsPerProviderContract() {
        assertEquals(emptyList<Pair<String, String>>(), buildAuthHeaders(ProviderProtocol.ANTHROPIC, ""))

        val anthropic = buildAuthHeaders(ProviderProtocol.ANTHROPIC, "key")
        assertTrue(anthropic.contains("Authorization" to "Bearer key"))
        assertTrue(anthropic.contains("x-api-key" to "key"))
        assertTrue(anthropic.contains("anthropic-version" to "2023-06-01"))

        val openAi = buildAuthHeaders(ProviderProtocol.OPENAI_CHAT, "key")
        assertTrue(openAi.contains("Authorization" to "Bearer key"))
        assertFalse(openAi.any { it.first == "x-api-key" })

        val router = buildAuthHeaders(ProviderProtocol.OPENROUTER, "key")
        assertTrue(router.contains("Authorization" to "Bearer key"))
        assertFalse(router.any { it.first == "x-api-key" })
    }
}
