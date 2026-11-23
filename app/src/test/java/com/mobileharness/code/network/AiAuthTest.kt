package com.mobileharness.code.network

import com.mobileharness.code.model.AiAuthType
import com.mobileharness.code.model.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAuthTest {
    @Test
    fun bearerKeyForOpenAiCompatible() {
        val headers = buildAuthHeadersFor(ProviderProtocol.OPENAI_CHAT, AiAuthType.API_KEY, "sk-123")
        assertEquals(listOf("Authorization" to "Bearer sk-123"), headers)
    }

    @Test
    fun azureUsesApiKeyHeaderWithoutBearer() {
        val headers = buildAuthHeadersFor(ProviderProtocol.OPENAI_CHAT, AiAuthType.API_KEY_HEADER, "azure-key")
        assertEquals(listOf("api-key" to "azure-key"), headers)
        assertTrue(headers.none { it.first == "Authorization" })
    }

    @Test
    fun localServersSendNoAuth() {
        assertEquals(emptyList<Pair<String, String>>(), buildAuthHeadersFor(ProviderProtocol.OPENAI_CHAT, AiAuthType.NONE, ""))
        assertEquals(emptyList<Pair<String, String>>(), buildAuthHeadersFor(ProviderProtocol.OPENAI_CHAT, AiAuthType.NONE, "ignored"))
    }

    @Test
    fun anthropicFamilyAddsXApiKeyAndVersion() {
        val headers = buildAuthHeadersFor(ProviderProtocol.ANTHROPIC_GATEWAY, AiAuthType.API_KEY, "ant-key")
        assertTrue(headers.contains("Authorization" to "Bearer ant-key"))
        assertTrue(headers.contains("x-api-key" to "ant-key"))
        assertTrue(headers.any { it.first == "anthropic-version" })
    }

    @Test
    fun adapterRequiringAuthSendsNoBareHeader() {
        assertEquals(emptyList<Pair<String, String>>(), buildAuthHeadersFor(ProviderProtocol.OPENAI_CHAT, AiAuthType.AWS_SIGV4, "x"))
        assertEquals(emptyList<Pair<String, String>>(), buildAuthHeadersFor(ProviderProtocol.OPENAI_CHAT, AiAuthType.OAUTH, "x"))
        assertEquals(emptyList<Pair<String, String>>(), buildAuthHeadersFor(ProviderProtocol.OPENAI_CHAT, AiAuthType.GOOGLE_OAUTH, "x"))
    }
}
