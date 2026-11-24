package com.mobileharness.code.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProtocolResolutionTest {
    @Test
    fun customOpenAiChatResolvesForBothAgents() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile",
            providerId = "groq",
            dshApi = "openai-completions",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
    }

    @Test
    fun customResponsesDegradesToChatForClaudeCode() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o",
            providerId = "openai",
            dshApi = "openai-responses",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
    }

    @Test
    fun customAnthropicMessagesStaysAnthropicGateway() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.anthropic.com",
            model = "claude-sonnet-4-5",
            providerId = "anthropic",
            dshApi = "anthropic-messages",
        )
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
    }

    @Test
    fun customDefaultsToAnthropicGatewayWhenNoDshApi() {
        // Legacy custom profiles have no dshApi; they must keep working as Anthropic gateways.
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://example.test/anthropic",
            model = "custom-model",
        )
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun keyIdDefaultsToKindName() {
        assertEquals("DEEPSEEK", ProviderProfile(ProviderKind.DEEPSEEK).keyId)
        assertEquals("CUSTOM", ProviderProfile(ProviderKind.CUSTOM).keyId)
    }

    @Test
    fun keyIdUsesProviderIdWhenSet() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile",
            providerId = "groq",
            dshApi = "openai-completions",
        )
        assertEquals("groq", profile.keyId)
    }

    @Test
    fun existingKindsAreUnchanged() {
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(ProviderProfile(ProviderKind.NVIDIA_NIM), AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(ProviderProfile(ProviderKind.DEEPSEEK), AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(ProviderProfile(ProviderKind.KIMI), AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.CLAUDE_LOGIN, providerProtocolForAgent(ProviderProfile(ProviderKind.CLAUDE), AgentKind.CLAUDE_CODE))
    }

    @Test
    fun freeLlmApiUsesAnthropicGatewayForClaudeCode() {
        // Claude Code talks to FreeLLMAPI through its Anthropic Messages surface (/v1/messages).
        assertEquals(
            ProviderProtocol.ANTHROPIC_GATEWAY,
            providerProtocolForAgent(ProviderProfile(ProviderKind.FREE_LLM_API), AgentKind.CLAUDE_CODE),
        )
    }

    @Test
    fun freeLlmApiUsesOpenAiChatForDeepSeekHarness() {
        // DeepSeek Harness talks to FreeLLMAPI through its OpenAI-compatible /v1/chat/completions.
        assertEquals(
            ProviderProtocol.OPENAI_CHAT,
            providerProtocolForAgent(ProviderProfile(ProviderKind.FREE_LLM_API), AgentKind.DEEPSEEK_HARNESS),
        )
    }

    @Test
    fun freeLlmApiIsOfferedToBothCodingAgents() {
        assertTrue(ProviderKind.FREE_LLM_API in providersForAgent(AgentKind.CLAUDE_CODE))
        assertTrue(ProviderKind.FREE_LLM_API in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertFalse(ProviderKind.FREE_LLM_API in providersForAgent(AgentKind.ANTIGRAVITY))
    }

    @Test
    fun freeLlmApiDefaultsToOpenAiCompletions() {
        assertEquals("openai-completions", defaultDshApiForProvider(ProviderKind.FREE_LLM_API))
        assertEquals("auto", ProviderKind.FREE_LLM_API.defaultModel)
        assertEquals("http://localhost:3001/v1", ProviderKind.FREE_LLM_API.defaultBaseUrl)
    }
}
