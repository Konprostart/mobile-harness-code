package com.mobileharness.code.model

/**
 * A curated, popular model suggestion. Presets only fill the editable model field in the
 * provider settings — they never bypass the user's protocol, base URL or API key. The model id
 * is stored verbatim, so a preset stays meaningful for the provider kind it is shown for.
 *
 * The list is intentionally broad: strong, balanced and fast/cheap (or `:free`) models are
 * curated per provider so they can be mixed and matched through the existing rotation and
 * fallback mechanisms (OpenRouter provider order + `allow_fallbacks`, the Sidekick
 * FallbackChain, and the local-model fallback). Nothing here changes the wire protocol, the
 * base URL or the saved API key — a preset only fills the editable model id field.
 */
data class ModelPreset(
    val id: String,
    val label: String,
    val kinds: Set<ProviderKind>,
)

object ModelPresets {
    val ALL: List<ModelPreset> = listOf(
        // ── Claude / Anthropic Messages ──────────────────────────────────────────────
        ModelPreset("claude-sonnet-4-6", "Claude Sonnet 4.6", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-opus-4-6", "Claude Opus 4.6", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-haiku-4-6", "Claude Haiku 4.6 · fast", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),
        ModelPreset("claude-3-5-haiku-latest", "Claude 3.5 Haiku · fast", setOf(ProviderKind.ANTHROPIC, ProviderKind.CUSTOM)),

        // ── DeepSeek (Anthropic-compatible gateway) ───────────────────────────────────
        ModelPreset("deepseek-v4-pro", "DeepSeek V4 Pro", setOf(ProviderKind.DEEPSEEK, ProviderKind.CUSTOM, ProviderKind.NVIDIA_NIM)),
        ModelPreset("deepseek-v4-flash", "DeepSeek V4 Flash · fast", setOf(ProviderKind.DEEPSEEK, ProviderKind.CUSTOM)),
        ModelPreset("deepseek-chat", "DeepSeek-V3 (chat)", setOf(ProviderKind.DEEPSEEK, ProviderKind.KIMI, ProviderKind.CUSTOM, ProviderKind.NVIDIA_NIM)),
        ModelPreset("deepseek-reasoner", "DeepSeek-R1 (reasoner)", setOf(ProviderKind.DEEPSEEK, ProviderKind.KIMI, ProviderKind.CUSTOM, ProviderKind.NVIDIA_NIM)),

        // ── Kimi / Moonshot (Anthropic-compatible) ────────────────────────────────────
        ModelPreset("kimi-k2.6", "Kimi K2.6", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),
        ModelPreset("kimi-k2", "Kimi K2", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),
        ModelPreset("kimi-k2-thinking", "Kimi K2 Thinking", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),
        ModelPreset("kimi-latest", "Kimi Latest", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),
        ModelPreset("moonshot-v1-128k", "Moonshot v1 128K", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),
        ModelPreset("moonshot-v1-32k", "Moonshot v1 32K · fast", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),
        ModelPreset("moonshot-v1-8k", "Moonshot v1 8K · fast", setOf(ProviderKind.KIMI, ProviderKind.CUSTOM)),

        // ── OpenAI-compatible (native chat) ───────────────────────────────────────────
        ModelPreset("gpt-4o", "GPT-4o", setOf(ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)),
        ModelPreset("gpt-4.1", "GPT-4.1", setOf(ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)),
        ModelPreset("gpt-4o-mini", "GPT-4o mini · fast", setOf(ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)),
        ModelPreset("o3-mini", "o3-mini · fast", setOf(ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)),

        // ── OpenRouter (prefixed slugs — rotate/combine via provider order) ───────────
        // Claude
        ModelPreset("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("anthropic/claude-opus-4.6", "Claude Opus 4.6", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("anthropic/claude-haiku-4.6", "Claude Haiku 4.6 · fast", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", setOf(ProviderKind.LLM_ROUTER)),
        // DeepSeek
        ModelPreset("deepseek/deepseek-chat", "DeepSeek-V3", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("deepseek/deepseek-v3", "DeepSeek-V3", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("deepseek/deepseek-r1", "DeepSeek-R1", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("deepseek/deepseek-r1:free", "DeepSeek-R1 · free", setOf(ProviderKind.LLM_ROUTER)),
        // OpenAI
        ModelPreset("openai/gpt-4o", "GPT-4o", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("openai/gpt-4.1", "GPT-4.1", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("openai/gpt-4o-mini", "GPT-4o mini · fast", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("openai/o3-mini", "o3-mini · fast", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("openai/gpt-oss-120b", "GPT-OSS 120B", setOf(ProviderKind.LLM_ROUTER)),
        // Google
        ModelPreset("google/gemini-2.5-pro", "Gemini 2.5 Pro", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("google/gemini-2.5-flash", "Gemini 2.5 Flash · fast", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("google/gemini-2.0-flash", "Gemini 2.0 Flash · fast", setOf(ProviderKind.LLM_ROUTER)),
        // Meta
        ModelPreset("meta-llama/llama-4-maverick", "Llama 4 Maverick", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("meta-llama/llama-3.1-8b-instruct", "Llama 3.1 8B · fast", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B · free", setOf(ProviderKind.LLM_ROUTER)),
        // Qwen
        ModelPreset("qwen/qwen3-coder", "Qwen3 Coder", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("qwen/qwen2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("qwen/qwen3-coder:free", "Qwen3 Coder · free", setOf(ProviderKind.LLM_ROUTER)),
        // Mistral
        ModelPreset("mistralai/mistral-large-2411", "Mistral Large", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("mistralai/mistral-small-3.1-24b-instruct", "Mistral Small 3.1 · fast", setOf(ProviderKind.LLM_ROUTER)),
        // xAI
        ModelPreset("x-ai/grok-4", "Grok 4", setOf(ProviderKind.LLM_ROUTER)),
        ModelPreset("x-ai/grok-3", "Grok 3", setOf(ProviderKind.LLM_ROUTER)),
        // Moonshot
        ModelPreset("moonshotai/kimi-k2", "Kimi K2", setOf(ProviderKind.LLM_ROUTER)),

        // ── NVIDIA NIM curated coding models (OpenAI chat) ────────────────────────────
        ModelPreset("qwen/qwen3-coder-480b-a35b-instruct", "Qwen3 Coder 480B", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("qwen/qwen2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("qwen/qwen2.5-7b-instruct", "Qwen 2.5 7B · fast", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("deepseek-ai/deepseek-r1", "DeepSeek-R1", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("deepseek-ai/deepseek-v3", "DeepSeek-V3", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("meta/llama-3.3-70b-instruct", "Llama 3.3 70B", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("meta/llama-3.1-405b-instruct", "Llama 3.1 405B", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("meta/llama-3.1-8b-instruct", "Llama 3.1 8B · fast", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("nvidia/llama-3.1-nemotron-70b-instruct", "Nemotron 70B", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("mistralai/mistral-large-2-instruct", "Mistral Large 2", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("microsoft/phi-4-mini-instruct", "Phi-4 mini · fast", setOf(ProviderKind.NVIDIA_NIM)),
        ModelPreset("google/gemma-2-27b-it", "Gemma 2 27B", setOf(ProviderKind.NVIDIA_NIM)),

        // ── OpenCode Zen (gateway models; shown only for saved OpenCode Zen profiles) ──
        ModelPreset("deepseek-v4-flash", "DeepSeek V4 Flash · fast", setOf(ProviderKind.OPENCODE_ZEN)),
        ModelPreset("gpt-oss-120b", "GPT-OSS 120B", setOf(ProviderKind.OPENCODE_ZEN)),
        ModelPreset("qwen3-coder", "Qwen3 Coder", setOf(ProviderKind.OPENCODE_ZEN)),
    )

    /** Presets relevant to [kind]; the model id still needs the user's provider to accept it. */
    fun forKind(kind: ProviderKind): List<ModelPreset> =
        ALL.filter { kind in it.kinds }
}
