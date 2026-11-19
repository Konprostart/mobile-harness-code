package com.mobileharness.code.sidekick

import com.mobileharness.code.network.LocalNetwork
import java.net.URI

/**
 * Configuration for the optional on-device / local-network model server (Ollama, llama.cpp,
 * LM Studio, Strata…). These servers expose an OpenAI-compatible `/v1/chat/completions` endpoint
 * over plain HTTP on the loopback/private network and, by default, require no API key.
 *
 * The local model is a *fallback provider*, never a silent substitute for the primary provider:
 * it only receives traffic when the primary (and any same-provider fallback) fails with a
 * transient error (429 / 5xx / timeout / network), or when the device is offline.
 *
 * Everything here is pure and side-effect free so the endpoint construction, validation and the
 * no-key local-host guard are unit-testable without an Android context.
 */
data class LocalModelConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
) {
    /** True when the config is enabled, complete, and targets a local host only. */
    val hasUsableEndpoint: Boolean
        get() = enabled && model.isNotBlank() && baseUrl.isNotBlank() && isAllowedBaseUrl(baseUrl)

    companion object {
        /** Ollama's OpenAI-compatible API lives under `/v1`; `chat/completions` is appended later. */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"

        /** A small, widely-available coding model pulled with `ollama pull qwen2.5-coder:7b`. */
        const val DEFAULT_MODEL = "qwen2.5-coder:7b"

        /** Trims the trailing slash so endpoint concatenation never doubles it. */
        fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

        /**
         * The local fallback sends requests with no API key, so it must only ever target a
         * loopback, private, link-local, Tailscale or local-only host. This prevents a
         * misconfigured public URL from silently receiving key-less traffic.
         */
        fun isAllowedBaseUrl(raw: String): Boolean {
            val normalized = normalizeBaseUrl(raw)
            if (normalized.isBlank()) return false
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host ?: return false
            return LocalNetwork.isLocalHost(host)
        }

        /** OpenAI-compatible chat-completions endpoint for a normalized base URL. */
        fun chatCompletionsEndpoint(normalizedBaseUrl: String): String =
            "${normalizeBaseUrl(normalizedBaseUrl)}/chat/completions"
    }
}
