package com.mobileharness.code.sidekick

import com.mobileharness.code.model.ProviderProtocol
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.protocolFromDshApi
import com.mobileharness.code.network.ModelRoute

/**
 * Provider parameters resolved for a single route in the Sidekick fallback chain.
 *
 * The `"local"` route targets the user's on-device/LAN model server and sends no API key; every
 * other route reuses the primary provider (same base URL, key and wire protocol). Keeping this a
 * pure, deterministic function makes the whole routing decision unit-testable without a context.
 */
data class SidekickRouteTarget(
    val baseUrl: String,
    val apiKey: String,
    val protocol: ProviderProtocol,
)

/**
 * Builds the ordered fallback chain for the Sidekick assistant.
 *
 * Order: primary model → optional same-provider fallback model → optional local model. The local
 * model is deduped against both the primary and the fallback so a chain never contains the same
 * model twice, and it is only included when the user has enabled a usable local endpoint.
 */
fun buildSidekickRoutes(
    primaryModel: String,
    fallbackEnabled: Boolean,
    fallbackModel: String,
    local: LocalModelConfig,
): List<ModelRoute> {
    val routes = mutableListOf<ModelRoute>()
    routes += ModelRoute("primary", primaryModel)

    if (fallbackEnabled && fallbackModel.isNotBlank() && fallbackModel != primaryModel) {
        routes += ModelRoute("fallback", fallbackModel)
    }

    if (local.hasUsableEndpoint) {
        val existingModels = routes.map { it.model }
        if (local.model !in existingModels) {
            routes += ModelRoute("local", local.model)
        }
    }

    return routes
}

/**
 * Resolves the endpoint, key and protocol for one route. The `"local"` route uses the local
 * model server over the OpenAI chat-completions protocol and no key; anything else reuses the
 * primary provider profile.
 */
fun resolveSidekickRoute(
    route: ModelRoute,
    profile: ProviderProfile,
    primaryApiKey: String,
    local: LocalModelConfig,
): SidekickRouteTarget =
    if (route.id == "local") {
        SidekickRouteTarget(
            baseUrl = LocalModelConfig.normalizeBaseUrl(local.baseUrl),
            apiKey = "",
            protocol = ProviderProtocol.OPENAI_CHAT,
        )
    } else {
        SidekickRouteTarget(
            baseUrl = profile.resolvedBaseUrl,
            apiKey = primaryApiKey,
            protocol = if (profile.kind == ProviderKind.CUSTOM) {
                protocolFromDshApi(profile.dshApi, claudeCode = false)
            } else {
                profile.kind.protocol
            },
        )
    }
