package com.mobileharness.code.network

import com.mobileharness.code.model.AiAuthType
import com.mobileharness.code.model.ProviderProtocol

/**
 * Authentication headers for a provider request, parameterized by the provider's auth scheme.
 *
 * The original [ProviderApiClient.buildAuthHeaders] only knew two contracts (Bearer for
 * OpenAI-compatible endpoints, Bearer + `x-api-key` + `anthropic-version` for the Anthropic
 * family). This variant keeps those exact contracts and adds the few real header variations the
 * central library advertises:
 *
 *  - [AiAuthType.API_KEY] / [AiAuthType.BEARER_TOKEN] → `Authorization: Bearer <key>`
 *  - [AiAuthType.API_KEY_HEADER] (Azure OpenAI)          → `api-key: <key>` (no Bearer)
 *  - [AiAuthType.NONE] (local servers)                  → no auth headers
 *  - [AiAuthType.OAUTH] / [AiAuthType.GOOGLE_OAUTH] /
 *    [AiAuthType.AWS_SIGV4]                              → empty: these providers need an adapter,
 *                                                          never a bare header.
 *
 * Purely deterministic so the per-provider contract is unit-testable without a network.
 */
fun buildAuthHeadersFor(
    protocol: ProviderProtocol,
    authType: AiAuthType,
    apiKey: String,
): List<Pair<String, String>> {
    if (apiKey.isBlank()) return emptyList()
    val headers = mutableListOf<Pair<String, String>>()
    val anthropicFamily = protocol != ProviderProtocol.OPENROUTER &&
        protocol != ProviderProtocol.OPENAI_CHAT &&
        protocol != ProviderProtocol.OPENAI_RESPONSES

    when (authType) {
        AiAuthType.API_KEY_HEADER -> headers += "api-key" to apiKey
        AiAuthType.NONE -> Unit
        AiAuthType.OAUTH, AiAuthType.GOOGLE_OAUTH, AiAuthType.AWS_SIGV4 -> Unit
        AiAuthType.API_KEY, AiAuthType.BEARER_TOKEN -> headers += "Authorization" to "Bearer $apiKey"
    }

    if (anthropicFamily && authType in setOf(AiAuthType.API_KEY, AiAuthType.BEARER_TOKEN)) {
        headers += "x-api-key" to apiKey
        headers += "anthropic-version" to ANTHROPIC_VERSION
    }
    return headers
}
