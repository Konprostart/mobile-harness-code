package com.mobileharness.code.sidekick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelConfigTest {

    @Test
    fun normalizeBaseUrlTrimsWhitespaceAndTrailingSlash() {
        assertEquals("http://127.0.0.1:11434/v1", LocalModelConfig.normalizeBaseUrl("  http://127.0.0.1:11434/v1/  "))
        assertEquals("http://10.0.0.5:1234/v1", LocalModelConfig.normalizeBaseUrl("http://10.0.0.5:1234/v1///"))
    }

    @Test
    fun localAndPrivateHostsAreAllowed() {
        assertTrue(LocalModelConfig.isAllowedBaseUrl("http://127.0.0.1:11434/v1"))
        assertTrue(LocalModelConfig.isAllowedBaseUrl("http://localhost:11434"))
        assertTrue(LocalModelConfig.isAllowedBaseUrl("http://192.168.1.20:11434/v1"))
        assertTrue(LocalModelConfig.isAllowedBaseUrl("http://10.0.0.5:1234"))
        assertTrue(LocalModelConfig.isAllowedBaseUrl("https://model-server.lan/v1"))
        assertTrue(LocalModelConfig.isAllowedBaseUrl("http://[::1]:11434/v1"))
    }

    @Test
    fun publicHostsAreRejected() {
        assertFalse(LocalModelConfig.isAllowedBaseUrl("http://evil.example.com/v1"))
        assertFalse(LocalModelConfig.isAllowedBaseUrl("https://api.openai.com/v1"))
        assertFalse(LocalModelConfig.isAllowedBaseUrl("http://8.8.8.8/v1"))
    }

    @Test
    fun nonHttpSchemesAndBlankAreRejected() {
        assertFalse(LocalModelConfig.isAllowedBaseUrl("ftp://127.0.0.1/v1"))
        assertFalse(LocalModelConfig.isAllowedBaseUrl("file:///tmp"))
        assertFalse(LocalModelConfig.isAllowedBaseUrl(""))
        assertFalse(LocalModelConfig.isAllowedBaseUrl("   "))
    }

    @Test
    fun chatCompletionsEndpointAppendsPathWithoutDoublingSlash() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            LocalModelConfig.chatCompletionsEndpoint("http://127.0.0.1:11434/v1/"),
        )
    }

    @Test
    fun usableEndpointRequiresEnabledCompleteAndLocal() {
        val usable = LocalModelConfig(
            enabled = true,
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "qwen2.5-coder:7b",
        )
        assertTrue(usable.hasUsableEndpoint)

        assertFalse(usable.copy(enabled = false).hasUsableEndpoint)
        assertFalse(usable.copy(model = "").hasUsableEndpoint)
        assertFalse(usable.copy(baseUrl = "https://evil.example.com/v1").hasUsableEndpoint)
    }
}
