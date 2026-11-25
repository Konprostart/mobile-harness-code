package com.mobileharness.code.grom

import app.grom.sdk.connect.GromConnectException

/**
 * The result of trying to proxy one LLM request through GROM. It is deliberately
 * not a provider-specific type: the adapter is a bridge, not an AI component.
 */
sealed class GromLlmOutcome {
    data class Success(val result: Map<String, Any?>) : GromLlmOutcome()
    data class Unavailable(val reason: String) : GromLlmOutcome()
    data class Failed(val code: String, val message: String) : GromLlmOutcome()
}

/**
 * Bridges the existing Free LLM API provider onto GROM's service model, without
 * turning the universal connection manager into an AI-only component.
 *
 * Free LLM API is a full OpenAI/Anthropic-compatible HTTP gateway. GROM does not
 * ship such a gateway as a built-in service; instead this adapter speaks to a
 * *hypothetical* GROM service that publishes the `llm.proxy` capability and the
 * `chat.completions` / `messages` ops to scoped callers. When that service is
 * absent (the case for Grom v0.16.0, whose only services are `agent` and
 * `lightning`), the adapter reports [GromLlmOutcome.Unavailable] instead of
 * fabricating a reply — the caller falls back to the existing direct
 * `http://localhost:3001` server or shows a clear message.
 *
 * This is a separate, clearly-bounded component: it knows the LLM wire shapes,
 * while [GromConnectionManager] stays a generic, provider-agnostic gateway.
 */
class GromLlmProxyAdapter(private val gateway: GromServiceGateway) {

    suspend fun chatCompletions(request: Map<String, Any?>): GromLlmOutcome =
        proxy(OP_CHAT_COMPLETIONS, request)

    suspend fun messages(request: Map<String, Any?>): GromLlmOutcome =
        proxy(OP_MESSAGES, request)

    /** True when GROM is connected and currently publishes a callable `llm.proxy` service. */
    suspend fun isAvailable(): Boolean {
        if (gateway.snapshot.value.phase != GromPhase.CONNECTED) return false
        return findProxyService()?.let { it.callable(OP_CHAT_COMPLETIONS) || it.callable(OP_MESSAGES) } == true
    }

    private suspend fun proxy(op: String, request: Map<String, Any?>): GromLlmOutcome {
        val snapshot = gateway.snapshot.value
        if (snapshot.phase != GromPhase.CONNECTED) {
            return GromLlmOutcome.Unavailable("GROM is not connected (${snapshot.phase.name})")
        }
        val service = findProxyService()
            ?: return GromLlmOutcome.Unavailable(
                "GROM exposes no llm.proxy service; Free LLM API needs a Grom plugin with capability " +
                    "\"$CAPABILITY\" (or the direct localhost:3001 server)",
            )
        if (!service.callable(op)) {
            return GromLlmOutcome.Unavailable("GROM service '${service.id}' does not publish '$op' to scoped callers")
        }
        return try {
            GromLlmOutcome.Success(gateway.call(service.id, op, request).result)
        } catch (e: GromConnectException) {
            GromLlmOutcome.Failed(e.code, e.message ?: "GROM refused the request")
        }
    }

    private suspend fun findProxyService() =
        gateway.services().firstOrNull { CAPABILITY in it.capabilities }

    companion object {
        /** Capability a GROM service must publish to be usable as a Free LLM API proxy. */
        const val CAPABILITY: String = "llm.proxy"
        const val OP_CHAT_COMPLETIONS: String = "chat.completions"
        const val OP_MESSAGES: String = "messages"
    }
}
