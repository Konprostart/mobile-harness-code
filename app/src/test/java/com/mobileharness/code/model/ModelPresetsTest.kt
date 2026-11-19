package com.mobileharness.code.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun noDuplicateIdsWithinAKind() {
        // Two presets with the same id in the same provider would make the chips ambiguous.
        for (kind in ProviderKind.entries) {
            val ids = ModelPresets.forKind(kind).map { it.id }
            assertEquals(
                "duplicate model id under ${kind.name}",
                ids.size,
                ids.distinct().size,
            )
        }
    }

    @Test
    fun routerPresetsKeepProviderPrefixInvariant() {
        // The router fills model ids verbatim into OpenRouter's `model` field, which always
        // carries a `provider/model` slug. Guard against accidental unprefixed additions.
        assertFalse(ModelPresets.forKind(ProviderKind.LLM_ROUTER).any { "/" !in it.id })
    }

    @Test
    fun fastAndFreeTiersAreCurated() {
        // Strong/balanced/fast/free mix exists so users can rotate and combine.
        assertTrue(ModelPresets.forKind(ProviderKind.ANTHROPIC).any { it.id == "claude-haiku-4-6" })
        assertTrue(ModelPresets.forKind(ProviderKind.DEEPSEEK).any { it.id == "deepseek-v4-pro" })
        assertTrue(ModelPresets.forKind(ProviderKind.KIMI).any { it.id == "moonshot-v1-8k" })
        assertTrue(ModelPresets.forKind(ProviderKind.NVIDIA_NIM).any { it.id == "microsoft/phi-4-mini-instruct" })
        assertTrue(ModelPresets.forKind(ProviderKind.NVIDIA_NIM).any { it.id == "meta/llama-3.1-8b-instruct" })

        val router = ModelPresets.forKind(ProviderKind.LLM_ROUTER)
        assertTrue(router.any { it.id == "google/gemini-2.5-flash" })
        assertTrue(router.any { it.id == "openai/o3-mini" })
        assertTrue(router.any { it.id == "openai/gpt-oss-120b" })
        assertTrue(router.any { it.id == "x-ai/grok-4" })
        // Free tiers are clearly reachable (`:free` suffix) for cheap overnight runs.
        assertTrue(router.any { it.id.endsWith(":free") })
    }

    @Test
    fun openCodeZenPresetsAreSelfContained() {
        // OpenCode Zen is no longer offered as a first-party provider (third-party clients are
        // rejected), but saved profiles still load; keep its gateway models curated locally.
        val zen = ModelPresets.forKind(ProviderKind.OPENCODE_ZEN)
        assertTrue(zen.isNotEmpty())
        assertTrue(zen.all { "/" !in it.id })
    }
}
