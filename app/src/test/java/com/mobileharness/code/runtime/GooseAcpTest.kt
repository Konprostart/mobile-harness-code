package com.mobileharness.code.runtime

import com.mobileharness.code.model.AgentKind
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.model.providersForAgent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooseAcpTest {

    private fun json(line: String): JSONObject = JSONObject(line)

    // ------------------------------------------------------------------ request building

    @Test
    fun initializeIsJsonRpcWithNoFsDelegation() {
        val frame = json(GooseAcp.buildInitialize(1, "PocketDev", "1.0"))
        assertEquals("2.0", frame.getString("jsonrpc"))
        assertEquals(1L, frame.getLong("id"))
        assertEquals("initialize", frame.getString("method"))
        val caps = frame.getJSONObject("params").getJSONObject("clientCapabilities")
        assertFalse(caps.getJSONObject("fs").getBoolean("readTextFile"))
        assertFalse(caps.getBoolean("terminal"))
    }

    @Test
    fun sessionPromptWrapsTextBlock() {
        val frame = json(GooseAcp.buildSessionPrompt(3, "s1", "hello"))
        assertEquals("session/prompt", frame.getString("method"))
        val prompt = frame.getJSONObject("params").getJSONArray("prompt")
        assertEquals("text", prompt.getJSONObject(0).getString("type"))
        assertEquals("hello", prompt.getJSONObject(0).getString("text"))
    }

    @Test
    fun cancelIsNotificationWithoutId() {
        val frame = json(GooseAcp.buildCancel("s1"))
        assertEquals("session/cancel", frame.getString("method"))
        assertFalse(frame.has("id"))
    }

    @Test
    fun permissionResponsePrefersAllowOnce() {
        val options = listOf(
            GooseAcp.GoosePermissionOption("allow_always", "a1"),
            GooseAcp.GoosePermissionOption("allow_once", "a2"),
            GooseAcp.GoosePermissionOption("reject_once", "r1"),
        )
        val frame = json(GooseAcp.buildPermissionResponse(10, allow = true, options))
        assertEquals("selected", frame.getJSONObject("result").getString("outcome"))
        assertEquals("a2", frame.getJSONObject("result").getString("optionId"))
    }

    @Test
    fun permissionResponsePrefersRejectOnceOnDeny() {
        val options = listOf(
            GooseAcp.GoosePermissionOption("allow_once", "a2"),
            GooseAcp.GoosePermissionOption("reject_once", "r1"),
        )
        val frame = json(GooseAcp.buildPermissionResponse(10, allow = false, options))
        assertEquals("r1", frame.getJSONObject("result").getString("optionId"))
    }

    @Test
    fun permissionResponseCancelsWhenNoMatchingOption() {
        val options = listOf(GooseAcp.GoosePermissionOption("allow_always", "a1"))
        val frame = json(GooseAcp.buildPermissionResponse(10, allow = false, options))
        assertEquals("cancelled", frame.getJSONObject("result").getString("outcome"))
    }

    // ------------------------------------------------------------------ parsing

    @Test
    fun distinguishesResponseRequestAndNotification() {
        assertTrue(GooseAcp.isResponse(json("""{"jsonrpc":"2.0","id":3,"result":{}}""")))
        assertFalse(GooseAcp.isResponse(json("""{"jsonrpc":"2.0","method":"session/update"}""")))
        assertTrue(GooseAcp.isRequest(json("""{"jsonrpc":"2.0","id":9,"method":"session/request_permission"}""")))
        assertTrue(GooseAcp.isNotification(json("""{"jsonrpc":"2.0","method":"session/update"}""")))
    }

    @Test
    fun extractsSessionUpdateKind() {
        val line = json("""{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"text":"hi"}}}}""")
        assertEquals("agent_message_chunk", GooseAcp.sessionUpdateKind(line))
    }

    @Test
    fun extractsChunkText() {
        val update = json("""{"sessionUpdate":"agent_message_chunk","content":{"text":"hello world"}}""")
        assertEquals("hello world", GooseAcp.chunkText(update))
    }

    @Test
    fun extractsToolCallNameAndInput() {
        val update = json("""{"sessionUpdate":"tool_call","title":"shell","rawInput":{"command":"ls -la"},"_meta":{"goose":{"toolCall":{"toolName":"developer__shell"}}}}""")
        val tool = GooseAcp.toolCall(update)
        assertNotNull(tool)
        assertEquals("developer__shell", tool!!.name)
        assertEquals("ls -la", tool.input.getString("command"))
    }

    @Test
    fun extractsUsage() {
        val update = json("""{"sessionUpdate":"usage_update","totalTokens":120,"inputTokens":100,"outputTokens":20,"size":200000}""")
        val usage = GooseAcp.usage(update)
        assertNotNull(usage)
        assertEquals(120L, usage!!.totalTokens)
        assertEquals(200000L, usage.contextWindow)
    }

    @Test
    fun extractsPermissionRequest() {
        val line = json("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"title":"shell","rawInput":{"command":"rm -rf /tmp/x"},"_meta":{"goose":{"toolCall":{"toolName":"shell"}}}},"options":[{"kind":"allow_once","optionId":"o1"},{"kind":"reject_once","optionId":"o2"}]}}""")
        val permission = GooseAcp.permissionRequest(line)
        assertNotNull(permission)
        assertEquals("shell", permission!!.toolName)
        assertEquals("rm -rf /tmp/x", permission.input.getString("command"))
        assertEquals(2, permission.options.size)
    }

    @Test
    fun extractsPromptResult() {
        val line = json("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn","usage":{"totalTokens":10,"inputTokens":8,"outputTokens":2}}}""")
        val result = GooseAcp.promptResult(line)
        assertNotNull(result)
        assertEquals("end_turn", result!!.stopReason)
        assertEquals(10, result.usage!!.getInt("totalTokens"))
    }

    @Test
    fun extractsErrorMessage() {
        val line = json("""{"jsonrpc":"2.0","id":3,"error":{"code":-32000,"message":"Session not found"}}""")
        assertEquals("Session not found", GooseAcp.errorMessage(line))
        assertNull(GooseAcp.errorMessage(json("""{"jsonrpc":"2.0","id":3,"result":{}}""")))
    }

    // ------------------------------------------------------------------ provider env

    @Test
    fun providerEnvMapsAnthropicAndKey() {
        val profile = ProviderProfile(ProviderKind.ANTHROPIC, model = "claude-sonnet-4-6")
        val env = gooseProviderEnvironment(profile, "sk-secret")
        assertEquals("anthropic", env["GOOSE_PROVIDER"])
        assertEquals("claude-sonnet-4-6", env["GOOSE_MODEL"])
        assertEquals("sk-secret", env["GOOSE_PROVIDER__API_KEY"])
        assertEquals("approve", env["GOOSE_MODE"])
    }

    @Test
    fun providerEnvMapsOpenRouterAndCustomHost() {
        val openRouter = ProviderProfile(ProviderKind.LLM_ROUTER, model = "anthropic/claude-sonnet-4.6")
        assertEquals("openrouter", gooseProviderEnvironment(openRouter, "k")["GOOSE_PROVIDER"])

        val custom = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://api.example.com/v1", model = "gpt-4o")
        assertEquals("openai", gooseProviderEnvironment(custom, "k")["GOOSE_PROVIDER"])
        assertEquals("https://api.example.com/v1", gooseProviderEnvironment(custom, "k")["GOOSE_PROVIDER__HOST"])
    }

    @Test
    fun gooseProvidersExcludeOauthAndLocalGateway() {
        val providers = providersForAgent(AgentKind.GOOSE)
        assertTrue(ProviderKind.ANTHROPIC in providers)
        assertTrue(ProviderKind.LLM_ROUTER in providers)
        assertTrue(ProviderKind.DEEPSEEK in providers)
        assertTrue(ProviderKind.CUSTOM in providers)
        assertFalse(ProviderKind.CLAUDE in providers)
        assertFalse(ProviderKind.FREE_LLM_API in providers)
    }

    // ------------------------------------------------------------------ recovery profile

    @Test
    fun gooseRecoveryProfileIsFailClosedOnPersistButReportsTools() {
        assertFalse(GOOSE_RECOVERY_PROFILE.canResumePersisted)
        assertTrue(GOOSE_RECOVERY_PROFILE.reportsToolActivity)
        assertFalse(GOOSE_RECOVERY_PROFILE.supportsTurnCheckpoint)
    }
}
