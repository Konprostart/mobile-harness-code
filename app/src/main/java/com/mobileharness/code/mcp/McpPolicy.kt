package com.mobileharness.code.mcp

import com.mobileharness.code.network.LocalNetwork
import com.mobileharness.code.runtime.EcoMode
import java.net.URI

/**
 * Pure, fail-closed safety policy for MCP tool execution. It decides where a server may live, how
 * much data may cross the wire, and whether a tool's name/description suggests a side effect.
 *
 * The MCP client never sees an API key from the app vault: a server is only ever sent its own,
 * user-entered [McpServerConfig.bearerToken]. This policy additionally keeps the URL space tight
 * so a misconfigured server cannot silently receive key-less traffic toward a public host.
 */
object McpPolicy {
    /**
     * HTTPS is always allowed for an external MCP server. Plain HTTP is allowed only for
     * loopback/private/link-local/Tailscale/local hosts, mirroring [LocalNetwork.isPlainHttpAllowed]
     * for provider URLs. Everything else is rejected fail-closed.
     */
    fun isAllowedHttpUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host ?: return false
        return when (scheme) {
            "https" -> host.isNotBlank()
            "http" -> LocalNetwork.isLocalHost(host)
            else -> false
        }
    }

    private val MUTATING_MARKERS = listOf(
        "delete", "remove", "write", "create", "update", "install", "uninstall",
        "execute", "run", "send", "post", "upload", "deploy", "publish", "push",
        " rm ", " mv ", " cp ", "kill", "stop", "start", "restart", "modify", "edit",
        "patch", "put", "drop", "truncate", "rename", "set ", "commit", "checkout",
        "grant", "revoke", "delete-", "-delete", "destroy", "purge", "format", "reset",
    )

    /**
     * Heuristic mutating-tool detection from the tool name and description. Conservative: when in
     * doubt a tool is treated as mutating, which only makes the guard stricter (fail-closed).
     */
    fun isMutating(name: String, description: String): Boolean {
        val haystack = " ${name.lowercase()} ${description.lowercase()} "
        return MUTATING_MARKERS.any { haystack.contains(it) }
    }

    /** Maximum JSON argument size accepted for a tool call. */
    fun maxInputChars(ecoEnabled: Boolean): Int = if (ecoEnabled) 4_000 else 16_000

    /** Maximum textual tool output returned to the model (bounds token and memory usage). */
    fun maxOutputChars(ecoEnabled: Boolean): Int = if (ecoEnabled) 2_000 else 16_000

    /** Tool-call timeout; Eco Mode trades latency for a shorter, safer budget. */
    fun timeoutMillis(ecoEnabled: Boolean): Long = if (ecoEnabled) 15_000L else 30_000L

    /** Maximum number of tool round-trips a single assistant turn may perform. */
    const val MAX_TOOL_ROUNDS = 2

    /** Truncates text to [maxChars] with an ellipsis, without splitting surrogate pairs. */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val cut = maxChars - 1
        val safe = if (cut > 0 && Character.isHighSurrogate(text[cut])) cut - 1 else cut
        return text.substring(0, safe.coerceAtLeast(0)) + "…"
    }

    /** Convenience bridge to the Eco Mode completion budget. */
    fun ecoAwareMaxTokens(ecoEnabled: Boolean): Int = EcoMode.recommendedMaxTokens(ecoEnabled)

    /**
     * Maps an HTTP status code from an MCP server to a failure kind. 401/403 are authentication
     * failures (never retried against another server automatically), 408 is a timeout, and every
     * other non-2xx status is a generic network/protocol failure.
     */
    fun classifyHttpStatus(code: Int): McpCallFailureKind = when (code) {
        401, 403 -> McpCallFailureKind.AUTH
        408 -> McpCallFailureKind.TIMEOUT
        else -> McpCallFailureKind.NETWORK
    }
}
