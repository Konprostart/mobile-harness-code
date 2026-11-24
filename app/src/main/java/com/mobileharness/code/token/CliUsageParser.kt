package com.mobileharness.code.token

import com.mobileharness.code.model.ProviderProtocol
import org.json.JSONObject

/**
 * Parsed usage from a CLI agent's terminal event. [usage] is empty when the CLI did not report a
 * metered usage object (the caller then marks the entry NOT_TRACKED, or estimates separately and
 * marks it ESTIMATED). [reportedCostUsd] is only populated when the CLI itself reported a cost
 * (e.g. Claude Code `total_cost_usd`) — it is never derived by the app from token counts here.
 */
data class CliUsageResult(
    val usage: TokenUsage,
    val model: String? = null,
    val reportedCostUsd: Double? = null,
    val durationMillis: Long? = null,
    val sessionId: String? = null,
) {
    val hasUsage: Boolean get() = !usage.isEmpty
}

/**
 * Extracts usage from external CLI agents' stream-json terminal events without parsing free text.
 *
 * Only structured `usage` objects are read:
 *  - Claude Code `result` event exposes an Anthropic-shaped `usage` object plus `total_cost_usd`,
 *    `duration_ms`, `session_id` and `modelUsage`. This is authoritative and yields ACTUAL usage.
 *  - A generic `usage` object (OpenAI- or Anthropic-shaped) is honored when present.
 *
 * When no structured usage exists the parser returns null/empty so the caller can honestly mark the
 * entry NOT_TRACKED rather than fabricating token counts.
 */
object CliUsageParser {
    /** Parses a Claude Code stream-json `result` event. Returns null only when nothing usable exists. */
    fun parseClaudeResult(json: JSONObject): CliUsageResult? {
        val usage = json.optJSONObject("usage")
            ?.let { TokenUsageParser.parseUsageObject(it, ProviderProtocol.ANTHROPIC) }
            ?: TokenUsage.ZERO

        val model = json.optJSONObject("modelUsage")
            ?.keys()
            ?.asSequence()
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("model").takeIf { it.isNotBlank() }

        val cost = json.optDouble("total_cost_usd", Double.NaN).takeUnless { it.isNaN() }
        val duration = json.optLong("duration_ms", -1L).takeIf { it >= 0L }
        val sessionId = json.optString("session_id").takeIf { it.isNotBlank() }

        if (usage.isEmpty && cost == null && model == null) return null
        return CliUsageResult(usage, model, cost, duration, sessionId)
    }

    /** Extracts a structured `usage` object from a generic terminal event, or null when absent. */
    fun extractUsage(json: JSONObject, protocol: ProviderProtocol): TokenUsage? {
        val usageObj = json.optJSONObject("usage")
            ?: json.optJSONObject("result")?.optJSONObject("usage")
            ?: return null
        return TokenUsageParser.parseUsageObject(usageObj, protocol).takeUnless { it.isEmpty }
    }
}
