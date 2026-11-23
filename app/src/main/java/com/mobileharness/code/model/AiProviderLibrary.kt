package com.mobileharness.code.model

import java.util.Locale

/**
 * Central, shared library of AI providers, model routers, API gateways and local model servers.
 *
 * This is a single source of truth for the base URLs the app offers across every AI tool
 * (DeepSeek Harness, Claude Code, the Sidekick assistant and any future module). Selecting a
 * provider auto-fills its [AiProvider.baseUrl], wire [AiProvider.wireProtocol] and
 * [AiProvider.defaultModel] into the existing "Custom API" profile; no other part of the app
 * keeps its own copy of these endpoints.
 *
 * All URLs are the provider's documented public endpoints. Providers that do not speak one of the
 * wire protocols the app understands (Anthropic Messages, OpenAI chat-completions or OpenAI
 * Responses) are still listed for transparency but marked [AiProvider.needsAdapter] so the UI can
 * clearly state they require an adapter and are not directly usable.
 */
enum class AiProviderCategory(val label: String) {
    OFFICIAL("Official AI Providers"),
    ROUTER("AI Model Routers"),
    GATEWAY("API Gateways"),
    OPENAI_COMPATIBLE("OpenAI Compatible Providers"),
    LOCAL("Local AI Servers"),
    CUSTOM("Custom Providers"),
}

/** Wire protocol the app should use when talking to a provider (mirrors the DSH custom-route API). */
enum class AiWireProtocol(val dshApi: String?) {
    ANTHROPIC_MESSAGES("anthropic-messages"),
    OPENAI_CHAT("openai-completions"),
    OPENAI_RESPONSES("openai-responses"),
    /** Provider speaks its own protocol (or requires SigV4/OAuth); an adapter is required. */
    NATIVE(null),
}

/** A specific capability a provider advertises. Unknown is omitted rather than assumed. */
enum class AiCapability(val label: String) {
    CHAT_COMPLETIONS("Chat Completions"),
    RESPONSES_API("Responses API"),
    ANTHROPIC_MESSAGES("Anthropic Messages"),
    GEMINI_API("Gemini API"),
    STREAMING("Streaming"),
    TOOL_CALLING("Tool Calling"),
    VISION("Vision"),
    EMBEDDINGS("Embeddings"),
}

enum class AiAuthType(val label: String) {
    API_KEY("API key (Bearer)"),
    API_KEY_HEADER("API key (api-key header)"),
    BEARER_TOKEN("Bearer token"),
    OAUTH("OAuth"),
    GOOGLE_OAUTH("Google OAuth / service account"),
    AWS_SIGV4("AWS SigV4 credentials"),
    NONE("None (local server)"),
}

data class AiProvider(
    val id: String,
    val name: String,
    val category: AiProviderCategory,
    val baseUrl: String,
    val wireProtocol: AiWireProtocol,
    val defaultModel: String,
    val authType: AiAuthType,
    val apiFormats: Set<AiWireProtocol>,
    val capabilities: Set<AiCapability>,
    val docsUrl: String,
    val notes: String = "",
    val official: Boolean = true,
) {
    /** A provider the app cannot talk to directly (its own API, or requires an adapter). */
    val needsAdapter: Boolean get() = wireProtocol == AiWireProtocol.NATIVE

    /** The DSH custom-route wire value this provider maps to (null when an adapter is required). */
    val dshApi: String? get() = wireProtocol.dshApi

    /** Primary search text: name + id + category so partial matches surface the provider. */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return true
        return name.lowercase(Locale.ROOT).contains(q) ||
            id.lowercase(Locale.ROOT).contains(q) ||
            category.label.lowercase(Locale.ROOT).contains(q)
    }
}

/**
 * The bundled provider catalog. It is intentionally a plain Kotlin list so it works fully offline
 * and never requires a network round-trip to render the library. To add a provider, append an
 * [AiProvider] entry here — no other code changes are required, and it becomes available to every
 * AI tool through the shared picker.
 */
object AiProviderCatalog {
    val all: List<AiProvider> = listOf(
        // ── Official AI Providers ──────────────────────────────────────────────────────────────
        AiProvider(
            id = "openai", name = "OpenAI", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.openai.com/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4o", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.OPENAI_RESPONSES),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.RESPONSES_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://platform.openai.com/docs/api-reference",
        ),
        AiProvider(
            id = "anthropic", name = "Anthropic (Claude)", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.anthropic.com", wireProtocol = AiWireProtocol.ANTHROPIC_MESSAGES,
            defaultModel = "claude-sonnet-4-5", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.ANTHROPIC_MESSAGES),
            capabilities = setOf(AiCapability.ANTHROPIC_MESSAGES, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://docs.anthropic.com/en/api/messages",
        ),
        AiProvider(
            id = "google-gemini", name = "Google Gemini", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gemini-2.5-flash", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.NATIVE),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.GEMINI_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://ai.google.dev/gemini-api/docs/openai",
        ),
        AiProvider(
            id = "deepseek", name = "DeepSeek", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.deepseek.com/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "deepseek-chat", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.ANTHROPIC_MESSAGES),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://api-docs.deepseek.com",
            notes = "Also exposes an Anthropic-compatible endpoint at https://api.deepseek.com/anthropic.",
        ),
        AiProvider(
            id = "qwen", name = "Qwen (Alibaba DashScope)", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "qwen-plus", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope",
        ),
        AiProvider(
            id = "moonshot", name = "Moonshot AI (Kimi)", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.moonshot.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "moonshot-v1-8k", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.ANTHROPIC_MESSAGES),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://platform.moonshot.ai/docs",
        ),
        AiProvider(
            id = "mistral", name = "Mistral AI", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.mistral.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "mistral-large-latest", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://docs.mistral.ai/api/",
        ),
        AiProvider(
            id = "xai", name = "xAI (Grok)", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.x.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "grok-4", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://docs.x.ai/docs/overview",
        ),
        AiProvider(
            id = "cohere", name = "Cohere", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.cohere.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "command-r-plus", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.NATIVE),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.EMBEDDINGS),
            docsUrl = "https://docs.cohere.com/reference/chat",
            notes = "Native Cohere API differs; use the OpenAI-compatible /v1 path for chat.",
        ),
        AiProvider(
            id = "perplexity", name = "Perplexity", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.perplexity.ai", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "sonar", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://docs.perplexity.ai/api-reference/chat-completions",
        ),
        AiProvider(
            id = "huggingface", name = "Hugging Face Inference", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://router.huggingface.co/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://huggingface.co/docs/inference-providers",
        ),
        AiProvider(
            id = "replicate", name = "Replicate", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.replicate.com/v1", wireProtocol = AiWireProtocol.NATIVE,
            defaultModel = "meta/meta-llama-3-70b-instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.NATIVE),
            capabilities = setOf(AiCapability.STREAMING),
            docsUrl = "https://replicate.com/docs/reference/http",
            notes = "Proprietary predictions API, not OpenAI-compatible — requires an adapter.",
        ),
        AiProvider(
            id = "cloudflare", name = "Cloudflare Workers AI", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://api.cloudflare.com/client/v4", wireProtocol = AiWireProtocol.NATIVE,
            defaultModel = "@cf/meta/llama-3.3-70b-instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.NATIVE),
            capabilities = setOf(AiCapability.STREAMING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://developers.cloudflare.com/workers-ai/",
            notes = "Requires an account ID and its own REST schema — adapter required.",
        ),
        AiProvider(
            id = "azure-openai", name = "Azure OpenAI", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://{resource}.openai.azure.com/openai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4o", authType = AiAuthType.API_KEY_HEADER,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.OPENAI_RESPONSES),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.RESPONSES_API, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://learn.microsoft.com/azure/ai-services/openai/reference",
            notes = "Replace {resource} with your Azure resource name; uses an api-key header.",
        ),
        AiProvider(
            id = "amazon-bedrock", name = "Amazon Bedrock", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com", wireProtocol = AiWireProtocol.NATIVE,
            defaultModel = "anthropic.claude-3-5-sonnet-20241022-v2:0", authType = AiAuthType.AWS_SIGV4,
            apiFormats = setOf(AiWireProtocol.NATIVE),
            capabilities = setOf(AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://docs.aws.amazon.com/bedrock/latest/APIReference/",
            notes = "AWS SigV4 authentication, not an OpenAI/Anthropic endpoint — adapter required.",
        ),
        AiProvider(
            id = "google-vertex", name = "Google Vertex AI", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://{location}-aiplatform.googleapis.com/v1", wireProtocol = AiWireProtocol.NATIVE,
            defaultModel = "gemini-2.0-flash", authType = AiAuthType.GOOGLE_OAUTH,
            apiFormats = setOf(AiWireProtocol.NATIVE, AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.GEMINI_API, AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/call-vertex-using-openai-library",
            notes = "Requires a GCP project and OAuth/service-account credentials — adapter required.",
        ),
        AiProvider(
            id = "nvidia-nim", name = "NVIDIA NIM", category = AiProviderCategory.OFFICIAL,
            baseUrl = "https://integrate.api.nvidia.com/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "meta/llama-3.3-70b-instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.EMBEDDINGS),
            docsUrl = "https://build.nvidia.com/docs",
        ),

        // ── AI Model Routers ───────────────────────────────────────────────────────────────────
        AiProvider(
            id = "openrouter", name = "OpenRouter", category = AiProviderCategory.ROUTER,
            baseUrl = "https://openrouter.ai/api/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "anthropic/claude-sonnet-latest", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.ANTHROPIC_MESSAGES),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://openrouter.ai/docs/quickstart",
            notes = "Anthropic-compatible /api/v1/messages also available; use slug-style model ids.",
        ),
        AiProvider(
            id = "portkey", name = "Portkey", category = AiProviderCategory.ROUTER,
            baseUrl = "https://api.portkey.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4o", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://portkey.ai/docs/api-reference",
        ),
        AiProvider(
            id = "eden-ai", name = "Eden AI", category = AiProviderCategory.ROUTER,
            baseUrl = "https://api.edenai.run/v2", wireProtocol = AiWireProtocol.NATIVE,
            defaultModel = "openai/gpt-4o", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.NATIVE),
            capabilities = setOf(AiCapability.STREAMING),
            docsUrl = "https://docs.edenai.co/reference",
            notes = "Aggregator with its own multi-provider API — adapter required.",
        ),

        // ── API Gateways ───────────────────────────────────────────────────────────────────────
        AiProvider(
            id = "vercel-ai-gateway", name = "Vercel AI Gateway", category = AiProviderCategory.GATEWAY,
            baseUrl = "https://ai-gateway.vercel.sh/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4o", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://vercel.com/docs/ai-gateway",
        ),
        AiProvider(
            id = "helicone", name = "Helicone", category = AiProviderCategory.GATEWAY,
            baseUrl = "https://oai.helicone.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4o", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://docs.helicone.ai/getting-started/quick-start",
        ),
        AiProvider(
            id = "requesty", name = "Requesty", category = AiProviderCategory.GATEWAY,
            baseUrl = "https://router.requesty.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4o", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT, AiWireProtocol.ANTHROPIC_MESSAGES),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://docs.requesty.ai",
        ),
        AiProvider(
            id = "litellm", name = "LiteLLM Proxy", category = AiProviderCategory.GATEWAY,
            baseUrl = "http://localhost:4000/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4o", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://docs.litellm.ai/docs/proxy/quick_start",
            notes = "Self-hosted OpenAI-compatible proxy; key optional depending on config.",
            official = false,
        ),

        // ── OpenAI Compatible Providers ────────────────────────────────────────────────────────
        AiProvider(
            id = "together", name = "Together AI", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.together.xyz/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://docs.together.ai/reference/chat-completions",
        ),
        AiProvider(
            id = "fireworks", name = "Fireworks AI", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.fireworks.ai/inference/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "accounts/fireworks/models/llama-v3p1-70b-instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://docs.fireworks.ai/api-reference/post-chatcompletions",
        ),
        AiProvider(
            id = "groq", name = "Groq", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "llama-3.3-70b-versatile", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://console.groq.com/docs/openai",
        ),
        AiProvider(
            id = "cerebras", name = "Cerebras", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.cerebras.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "llama3.3-70b", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://inference-docs.cerebras.ai/api-reference/chat-completions",
        ),
        AiProvider(
            id = "sambanova", name = "SambaNova", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.sambanova.ai/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "Meta-Llama-3.3-70B-Instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://community.sambanova.ai/t/open-ai-compatibility/195",
        ),
        AiProvider(
            id = "siliconflow", name = "SiliconFlow", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.siliconflow.cn/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "deepseek-ai/DeepSeek-V3", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://docs.siliconflow.cn/en/api-reference/chat-completions/chat-completions",
        ),
        AiProvider(
            id = "novita", name = "Novita AI", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.novita.ai/v3/openai", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "deepseek/deepseek_v3", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://novita.ai/docs/api-reference/chat-completions",
        ),
        AiProvider(
            id = "deepinfra", name = "DeepInfra", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.deepinfra.com/v1/openai", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://deepinfra.com/docs/advanced/openai_api",
        ),
        AiProvider(
            id = "hyperbolic", name = "Hyperbolic", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.hyperbolic.xyz/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://docs.hyperbolic.xyz/docs/quickstart",
        ),
        AiProvider(
            id = "aimlapi", name = "AI/ML API", category = AiProviderCategory.OPENAI_COMPATIBLE,
            baseUrl = "https://api.aimlapi.com/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION),
            docsUrl = "https://docs.aimlapi.com/api-reference/endpoints/chat-completions",
        ),

        // ── Local AI Servers ───────────────────────────────────────────────────────────────────
        AiProvider(
            id = "ollama", name = "Ollama", category = AiProviderCategory.LOCAL,
            baseUrl = "http://localhost:11434/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "llama3.3", authType = AiAuthType.NONE,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://ollama.com/blog/openai-compatibility",
            notes = "Runs on the phone/PC; no API key required.",
        ),
        AiProvider(
            id = "lm-studio", name = "LM Studio", category = AiProviderCategory.LOCAL,
            baseUrl = "http://localhost:1234/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "local-model", authType = AiAuthType.NONE,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://lmstudio.ai/docs/api/openai-api",
            notes = "Runs on the phone/PC; no API key required.",
        ),
        AiProvider(
            id = "vllm", name = "vLLM", category = AiProviderCategory.LOCAL,
            baseUrl = "http://localhost:8000/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct", authType = AiAuthType.NONE,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://docs.vllm.ai/en/latest/serving/openai_compatible_server.html",
            notes = "OpenAI-compatible server; no API key required by default.",
        ),
        AiProvider(
            id = "localai", name = "LocalAI", category = AiProviderCategory.LOCAL,
            baseUrl = "http://localhost:8080/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "gpt-4", authType = AiAuthType.NONE,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING, AiCapability.VISION, AiCapability.EMBEDDINGS),
            docsUrl = "https://localai.io/features/openai-compatibility/",
            notes = "OpenAI drop-in replacement; no API key required by default.",
        ),
        AiProvider(
            id = "llamacpp", name = "llama.cpp server", category = AiProviderCategory.LOCAL,
            baseUrl = "http://localhost:8080/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "local", authType = AiAuthType.NONE,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://github.com/ggml-org/llama.cpp/tree/master/examples/server",
            notes = "OpenAI-compatible /v1 endpoint; no API key required.",
        ),
        AiProvider(
            id = "jan", name = "Jan", category = AiProviderCategory.LOCAL,
            baseUrl = "http://localhost:1337/v1", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "local-model", authType = AiAuthType.NONE,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "https://jan.ai/docs/api-reference",
            notes = "Local OpenAI-compatible server; no API key required.",
        ),

        // ── Custom Providers ───────────────────────────────────────────────────────────────────
        AiProvider(
            id = "openai-compatible", name = "OpenAI-compatible API", category = AiProviderCategory.CUSTOM,
            baseUrl = "", wireProtocol = AiWireProtocol.OPENAI_CHAT,
            defaultModel = "", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
            capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "",
            notes = "Any OpenAI chat-completions compatible server. Enter the base URL manually.",
            official = false,
        ),
        AiProvider(
            id = "custom-anthropic", name = "Anthropic-compatible API", category = AiProviderCategory.CUSTOM,
            baseUrl = "", wireProtocol = AiWireProtocol.ANTHROPIC_MESSAGES,
            defaultModel = "", authType = AiAuthType.API_KEY,
            apiFormats = setOf(AiWireProtocol.ANTHROPIC_MESSAGES),
            capabilities = setOf(AiCapability.ANTHROPIC_MESSAGES, AiCapability.STREAMING, AiCapability.TOOL_CALLING),
            docsUrl = "",
            notes = "Any Anthropic Messages compatible endpoint. Enter the base URL manually.",
            official = false,
        ),
    )

    val categories: List<AiProviderCategory> = AiProviderCategory.entries

    /** Last-accepted remote overlay (metadata only), applied on top of [all] by the catalog store. */
    @Volatile
    private var remoteOverlay: List<AiProvider> = emptyList()

    /**
     * The catalog the app should actually show: the bundled [all] plus any remote overlay merged
     * on top. Remote entries with an existing id override that provider; new ids are appended, and
     * the bundled catalog is never reduced.
     */
    fun current(): List<AiProvider> =
        if (remoteOverlay.isEmpty()) all else AiCatalogMerge.merge(all, remoteOverlay)

    /** Installs a validated remote overlay; never touches user configs or saved keys. */
    fun applyRemote(remote: List<AiProvider>) {
        remoteOverlay = remote
    }

    fun byId(id: String): AiProvider? = current().firstOrNull { it.id == id }

    /** Auth scheme for a library provider id; defaults to a Bearer key for unknown/custom ids. */
    fun authTypeFor(id: String): AiAuthType = byId(id)?.authType ?: AiAuthType.API_KEY

    fun byCategory(category: AiProviderCategory): List<AiProvider> = current().filter { it.category == category }

    fun search(query: String, category: AiProviderCategory? = null): List<AiProvider> {
        val q = query.trim()
        return current().filter {
            it.matches(q) && (category == null || it.category == category)
        }
    }

    /**
     * Fails loudly (in tests / CI) if the bundled catalog is internally inconsistent: duplicate
     * ids, blank names, malformed URLs, or providers that claim a directly-usable protocol but
     * have no dsh-api mapping.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (provider in all) {
            if (provider.id.isBlank()) problems += "blank id"
            if (!seen.add(provider.id)) problems += "duplicate id '${provider.id}'"
            if (provider.name.isBlank()) problems += "'${provider.id}' blank name"
            if (provider.defaultModel.isBlank() && !provider.needsAdapter && provider.id !in setOf("openai-compatible", "custom-anthropic")) {
                problems += "'${provider.id}' has no default model"
            }
            if (!provider.needsAdapter && provider.wireProtocol.dshApi == null) {
                problems += "'${provider.id}' directly-usable but no dsh mapping"
            }
            if (provider.baseUrl.isNotBlank()) {
                val ok = provider.baseUrl.startsWith("https://") ||
                    provider.baseUrl.startsWith("http://localhost") ||
                    provider.baseUrl.startsWith("http://127.0.0.1")
                if (!ok) problems += "'${provider.id}' invalid base URL '${provider.baseUrl}'"
            } else if (provider.category != AiProviderCategory.CUSTOM) {
                problems += "'${provider.id}' has blank base URL"
            }
            if (provider.docsUrl.isNotBlank() && !provider.docsUrl.startsWith("https://")) {
                problems += "'${provider.id}' docs URL must be https"
            }
        }
        return problems
    }
}
