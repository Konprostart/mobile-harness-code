package com.mobileharness.code.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPresetsTest {
    @Test
    fun everyPresetHasNonBlankIdAndLabelAndTargetKinds() {
        assertTrue(ModelPresets.ALL.isNotEmpty())
        for (preset in ModelPresets.ALL) {
            assertTrue("blank id", preset.id.isNotBlank())
            assertTrue("blank label", preset.label.isNotBlank())
            assertTrue("empty kinds for ${preset.id}", preset.kinds.isNotEmpty())
        }
    }

    @Test
    fun forKindReturnsOnlyRelevantPresets() {
        val anthropic = ModelPresets.forKind(ProviderKind.ANTHROPIC)
        assertTrue(anthropic.any { it.id == "claude-sonnet-4-6" })
        // Anthropic presets must not leak OpenRouter-prefixed slugs or GPT models.
        assertTrue(anthropic.none { it.id.startsWith("anthropic/") || it.id.startsWith("openai/") })

        val router = ModelPresets.forKind(ProviderKind.LLM_ROUTER)
        assertTrue(router.any { it.id == "deepseek/deepseek-r1" })
        assertTrue(router.all { it.id.contains("/") })

        val deepseek = ModelPresets.forKind(ProviderKind.DEEPSEEK)
        assertTrue(deepseek.any { it.id == "deepseek-reasoner" })
        assertTrue(deepseek.any { it.id == "deepseek-chat" })
    }

    @Test
    fun subscriptionProviderHasNoPresets() {
        assertEquals(emptyList<ModelPreset>(), ModelPresets.forKind(ProviderKind.CLAUDE))
    }
}
