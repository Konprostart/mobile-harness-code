package com.mobileharness.code.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCompatibilityTest {
    private fun provider(vararg capabilities: AiCapability) = AiProvider(
        id = "p", name = "P", category = AiProviderCategory.OFFICIAL,
        baseUrl = "https://api.example.com/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
        defaultModel = "m", authType = AiAuthType.API_KEY,
        apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
        capabilities = capabilities.toSet(),
        docsUrl = "https://docs.example.com",
    )

    @Test
    fun providerCapabilitiesAreUsedWhenModelIsUnknown() {
        val p = provider(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING)
        assertTrue(AiCompatibility.supports(p, "some-unlisted-model", AiCapability.STREAMING))
        assertFalse(AiCompatibility.supports(p, "some-unlisted-model", AiCapability.VISION))
    }

    @Test
    fun modelOverrideWinsOverProvider() {
        // Provider claims vision, but DeepSeek-chat is text-only.
        val p = provider(AiCapability.CHAT_COMPLETIONS, AiCapability.VISION)
        assertFalse(AiCompatibility.supports(p, "deepseek-chat", AiCapability.VISION))
        assertTrue(AiCompatibility.supports(p, "deepseek-chat", AiCapability.TOOL_CALLING))
    }

    @Test
    fun prefixedModelIdsMatchOverrides() {
        val p = provider(AiCapability.CHAT_COMPLETIONS, AiCapability.VISION)
        assertFalse(AiCompatibility.supports(p, "openai/deepseek-chat", AiCapability.VISION))
        assertFalse(AiCompatibility.supports(p, "deepseek-chat:free", AiCapability.VISION))
    }

    @Test
    fun unknownModelFailsClosedForEverything() {
        val p = provider(AiCapability.CHAT_COMPLETIONS)
        assertFalse(AiCompatibility.supportsStreaming(p, "model-with-no-override"))
    }
}
