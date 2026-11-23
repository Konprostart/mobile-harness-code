package com.mobileharness.code.model

/**
 * Central compatibility knowledge for providers and models.
 *
 * Every AI tool (DeepSeek Harness, Claude Code and the Sidekick assistant) must consult the same
 * capability data before it sends a request, so no tool ever asks a provider for streaming, tool
 * calling or vision that the provider (or a specific model) does not support.
 *
 * Resolution order:
 *  1. A curated per-model override in [knownModelCapabilities] (model-level knowledge wins).
 *  2. The provider's advertised [AiProvider.capabilities] (fallback for unlisted models).
 *  3. Unknown → the capability is reported as **unsupported** (fail-closed), never assumed.
 */
object AiCompatibility {
    /**
     * Curated overrides for well-known models whose capabilities differ from their provider's
     * broadest claims. Keys are lowercase model ids. A model in this map is the authoritative
     * capability set; a model not listed falls back to its provider's advertised capabilities.
     */
    val knownModelCapabilities: Map<String, Set<AiCapability>> = mapOf(
        // DeepSeek: strong chat + tools, but no vision.
        "deepseek-chat" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
        "deepseek-reasoner" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
        "deepseek-v4-flash" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
        "deepseek-v4-pro" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
        // OpenAI flagship chat models support vision + tools + streaming.
        "gpt-4o" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.RESPONSES_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "gpt-4o-mini" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.RESPONSES_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "gpt-4.1" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.RESPONSES_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "gpt-4.1-mini" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.RESPONSES_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        // Groq is text-only (no vision), but fast streaming + tools.
        "llama-3.3-70b-versatile" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
        // Anthropic Claude models.
        "claude-sonnet-4-5" to setOf(AiCapability.ANTHROPIC_MESSAGES, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "claude-sonnet-4-6" to setOf(AiCapability.ANTHROPIC_MESSAGES, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "claude-opus-4-6" to setOf(AiCapability.ANTHROPIC_MESSAGES, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "claude-haiku-4-6" to setOf(AiCapability.ANTHROPIC_MESSAGES, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        // Gemini via the OpenAI-compatible endpoint: vision + tools + streaming.
        "gemini-2.5-flash" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.GEMINI_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "gemini-2.0-flash" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.GEMINI_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
        "qwen-plus" to setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
    )

    /** The set of capabilities the app should treat as supported for `provider` + `model`. */
    fun capabilitiesFor(provider: AiProvider, model: String): Set<AiCapability> {
        val normalized = model.trim().lowercase()
        knownModelCapabilities[normalized]?.let { return it }
        // Model ids often carry provider prefixes/suffixes (e.g. "openai/gpt-4o" or "gpt-4o:free").
        knownModelCapabilities.entries.firstOrNull { (key, _) ->
            normalized == key || normalized.endsWith("/$key") || normalized.substringBefore(':') == key
        }?.let { return it.value }
        return provider.capabilities
    }

    fun supports(provider: AiProvider, model: String, capability: AiCapability): Boolean =
        capability in capabilitiesFor(provider, model)

    fun supportsStreaming(provider: AiProvider, model: String): Boolean =
        supports(provider, model, AiCapability.STREAMING)

    fun supportsToolCalling(provider: AiProvider, model: String): Boolean =
        supports(provider, model, AiCapability.TOOL_CALLING)

    fun supportsVision(provider: AiProvider, model: String): Boolean =
        supports(provider, model, AiCapability.VISION)

    fun supportsEmbeddings(provider: AiProvider, model: String): Boolean =
        supports(provider, model, AiCapability.EMBEDDINGS)
}
