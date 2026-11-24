package com.mobileharness.code.sidekick

import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.model.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidekickRoutingTest {

    private val profile = ProviderProfile(
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-4-6",
    )

    private fun local(enabled: Boolean = true, model: String = "qwen2.5-coder:7b") = LocalModelConfig(
        enabled = enabled,
        baseUrl = "http://127.0.0.1:11434/v1",
        model = model,
    )

    @Test
    fun primaryOnlyWhenNothingElseConfigured() {
        val routes = buildSidekickRoutes(
            primaryModel = "claude-sonnet-4-6",
            fallbackEnabled = false,
            fallbackModel = "",
            local = local(enabled = false),
        )
        assertEquals(listOf("primary"), routes.map { it.id })
    }

    @Test
    fun primaryThenFallbackThenLocal() {
        val routes = buildSidekickRoutes(
            primaryModel = "claude-sonnet-4-6",
            fallbackEnabled = true,
            fallbackModel = "deepseek-chat",
            local = local(),
        )
        assertEquals(listOf("primary", "fallback", "local"), routes.map { it.id })
        assertEquals(listOf("claude-sonnet-4-6", "deepseek-chat", "qwen2.5-coder:7b"), routes.map { it.model })
    }

    @Test
    fun localModelIsDedupedAgainstPrimaryAndFallback() {
        val routes = buildSidekickRoutes(
            primaryModel = "qwen2.5-coder:7b",
            fallbackEnabled = true,
            fallbackModel = "deepseek-chat",
            local = local(model = "qwen2.5-coder:7b"),
        )
        assertEquals(listOf("primary", "fallback"), routes.map { it.id })

        val routes2 = buildSidekickRoutes(
            primaryModel = "claude-sonnet-4-6",
            fallbackEnabled = true,
            fallbackModel = "qwen2.5-coder:7b",
            local = local(model = "qwen2.5-coder:7b"),
        )
        assertEquals(listOf("primary", "fallback"), routes2.map { it.id })
    }

    @Test
    fun blankFallbackModelIsSkipped() {
        val routes = buildSidekickRoutes(
            primaryModel = "claude-sonnet-4-6",
            fallbackEnabled = true,
            fallbackModel = "   ",
            local = local(),
        )
        assertEquals(listOf("primary", "local"), routes.map { it.id })
    }

    @Test
    fun disabledLocalModelIsNotIncluded() {
        val routes = buildSidekickRoutes(
            primaryModel = "claude-sonnet-4-6",
            fallbackEnabled = false,
            fallbackModel = "",
            local = local(enabled = false),
        )
        assertEquals(listOf("primary"), routes.map { it.id })
    }

    @Test
    fun localRouteUsesLocalBaseUrlNoKeyAndOpenAiChatProtocol() {
        val route = com.mobileharness.code.network.ModelRoute("local", "qwen2.5-coder:7b")
        val target = resolveSidekickRoute(route, profile, primaryApiKey = "sk-secret", local = local())
        assertEquals("http://127.0.0.1:11434/v1", target.baseUrl)
        assertEquals("", target.apiKey)
        assertEquals(ProviderProtocol.OPENAI_CHAT, target.protocol)
    }

    @Test
    fun nonLocalRouteReusesPrimaryProviderKeyAndProtocol() {
        val route = com.mobileharness.code.network.ModelRoute("primary", "claude-sonnet-4-6")
        val target = resolveSidekickRoute(route, profile, primaryApiKey = "sk-secret", local = local())
        assertEquals("https://api.anthropic.com", target.baseUrl)
        assertEquals("sk-secret", target.apiKey)
        assertEquals(ProviderProtocol.ANTHROPIC, target.protocol)
    }

    @Test
    fun emptyRoutesAreNeverProducedWhenPrimaryExists() {
        val routes = buildSidekickRoutes("claude-sonnet-4-6", false, "", local(enabled = false))
        assertTrue(routes.isNotEmpty())
    }

    @Test
    fun freeLlmApiUsesOpenAiChatProtocol() {
        val freeLlmApi = ProviderProfile(
            kind = ProviderKind.FREE_LLM_API,
            baseUrl = "http://localhost:3001/v1",
            model = "auto",
        )
        val route = com.mobileharness.code.network.ModelRoute("primary", "auto")
        val target = resolveSidekickRoute(route, freeLlmApi, primaryApiKey = "freellmapi-secret", local = local())
        assertEquals("http://localhost:3001/v1", target.baseUrl)
        assertEquals("freellmapi-secret", target.apiKey)
        assertEquals(ProviderProtocol.OPENAI_CHAT, target.protocol)
    }
}
