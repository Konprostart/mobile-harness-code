package com.mobileharness.code.mcp

import org.json.JSONObject

/**
 * Parses a model's *request* to invoke an MCP tool out of an assistant reply.
 *
 * The Sidekick system prompt instructs the model to emit a tool call as a fenced JSON block:
 *
 * ```
 * ```tool
 * {"tool":"search_docs","arguments":{"query":"..."}}
 * ```
 * ```
 *
 * A single leading `{"tool": …}` JSON object is also accepted. Everything else parses to null and
 * the assistant reply is returned verbatim. Pure and deterministic so the tool-loop decision is
 * unit-testable.
 */
object McpToolCallParser {
    data class Request(val toolName: String, val arguments: JSONObject)

    fun parse(text: String): Request? {
        val block = extractJsonBlock(text) ?: return null
        val root = runCatching { JSONObject(block) }.getOrNull() ?: return null
        val tool = root.optString("tool")
        if (tool.isBlank()) return null
        val args = root.optJSONObject("arguments") ?: JSONObject()
        return Request(tool, args)
    }

    /** Extracts the first fenced ```tool / ```json block, or a bare leading JSON object. */
    private fun extractJsonBlock(text: String): String? {
        val trimmed = text.trim()
        val fenced = FENCE.find(trimmed)
        if (fenced != null) {
            return fenced.groupValues[2].trim()
        }
        // A whole-message JSON object is accepted when it has a "tool" member.
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()?.takeIf { it.has("tool") }
                ?.let { trimmed }
        }
        return null
    }

    private val FENCE = Regex("(?s)```(?:tool|json)\\s*\\n?(.*?)```")
}
