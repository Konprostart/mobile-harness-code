package com.mobileharness.code.model

/**
 * A curated, popular model suggestion. Presets only fill the editable model field in the
 * provider settings — they never bypass the user's protocol, base URL or API key. The model id
 * is stored verbatim, so a preset stays meaningful for the provider kind it is shown for.
 */
data class ModelPreset(
    val id: String,
    val label: String,
    val kinds: Set<ProviderKind>,
)

object ModelPresets {
    val ALL: List<ModelPreset> = listOf(
        // Claude / Anthropic Messages
        ModelPreset("claude-sonnet-4-6", "Claude Sonnet 4.6", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-opus-4-6", "Claude Opus 4.6", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),

        // DeepSeek
        ModelPreset("deepseek-chat", "DeepSeek-V3 (chat)", setOf(ProviderKind.DEEPSEEK, ProviderKind.KIMI, ProviderKind.CUSTOM, ProviderKind.NVIDIA_NIM)),
        ModelPreset("deepseek-reasoner", "DeepSeek-R1 (reasoner)", setOf(ProviderKind.DEEPSEEK, ProviderKind.KIMI, ProviderKind.CUSTOM, ProviderKind.NVIDIA_NIM)),
        ModelPreset("deepseek-v4-flash", "DeepSeek V4 Flash", setOf(ProviderKind.DEEPSEEK, ProviderKind.CUSTOM)),

        // Kimi
        ModelPreset("kimi-k2.6", "Kimi K2.6", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),
        ModelPreset("kimi-k2-thinking", "Kimi K2 Thinking", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),

        // OpenAI-compatible
        ModelPreset("gpt-4o", "GPT-4o", setOf(ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)),
        ModelPreset("gpt-4o-mini", "GPT-4o mini", setOf(ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)),
        ModelPreset("gpt-4.1", "GPT-4.1", setOf(ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)),

        // OpenRouter (prefixed slugs)
        ModelPreset("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("deepseek/deepseek-chat", "DeepSeek-V3", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("deepseek/deepseek-r1", "DeepSeek-R1", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("openai/gpt-4o", "GPT-4o", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("openai/gpt-4o-mini", "GPT-4o mini", setOf(ProviderKind.LLM_ROUTER)),

        // NVIDIA NIM curated coding models
        ModelPreset("qwen/qwen2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("meta/llama-3.3-70b-instruct", "Llama 3.3 70B", setOf(ProviderKind.NVIDIA_NIM)),
    )

    /** Presets relevant to [kind]; the model id still needs the user's provider to accept it. */
    fun forKind(kind: ProviderKind): List<ModelPreset> =
        ALL.filter { kind in it.kinds }
}
