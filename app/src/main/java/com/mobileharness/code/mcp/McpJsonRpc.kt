package com.mobileharness.code.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic JSON-RPC 2.0 message construction and parsing for the Model Context Protocol.
 *
 * This object only builds and reads wire messages; it never opens a connection, so every function
 * is unit-testable without a network. Transport concerns (HTTP vs stdio) live in [McpClient].
 */
object McpJsonRpc {
    const val JSONRPC_VERSION = "2.0"
    const val PROTOCOL_VERSION = "2024-11-05"

    /** The MCP client identity announced during `initialize`. */
    fun clientInfo(clientVersion: String): JSONObject = JSONObject()
        .put("name", "mobile-harness-code")
        .put("version", clientVersion)

    /** `initialize` request (has an id; the server replies with its own capabilities). */
    fun initializeRequest(id: Long, clientVersion: String): String = envelope(id)
        .put("method", "initialize")
        .put(
            "params",
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", clientInfo(clientVersion)),
        )
        .toString()

    /** `notifications/initialized` notification (no id; completes the initialize handshake). */
    fun initializedNotification(): String = JSONObject()
        .put("jsonrpc", JSONRPC_VERSION)
        .put("method", "notifications/initialized")
        .toString()

    /** `tools/list` request. */
    fun toolsListRequest(id: Long): String = envelope(id)
        .put("method", "tools/list")
        .toString()

    /** `tools/call` request with the tool name and its JSON arguments. */
    fun toolsCallRequest(id: Long, name: String, arguments: JSONObject): String = envelope(id)
        .put("method", "tools/call")
        .put(
            "params",
            JSONObject()
                .put("name", name)
                .put("arguments", arguments),
        )
        .toString()

    /**
     * Extracts the tools from a `tools/list` response body. Returns an empty list when the body is
     * malformed, not an RPC response, or carries a JSON-RPC error — callers treat empty as "no
     * tools discovered" and remain fail-closed.
     */
    fun parseToolsList(body: String): List<McpTool> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (hasError(root)) return emptyList()
        val tools = root.optJSONObject("result")?.optJSONArray("tools") ?: return emptyList()
        return buildList {
            for (i in 0 until tools.length()) {
                val item = tools.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                val schema = item.opt("inputSchema")
                val schemaText = when (schema) {
                    is JSONObject -> schema.toString()
                    is String -> schema
                    else -> "{}"
                }
                add(McpTool(name, item.optString("description"), schemaText))
            }
        }
    }

    /** Parsed text output of a `tools/call` response. */
    data class ToolCallOutput(val text: String, val isError: Boolean)

    /** Extracts the textual content of a `tools/call` response, honoring the `isError` flag. */
    fun parseToolsCall(body: String): ToolCallOutput {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return ToolCallOutput("", isError = false)
        if (hasError(root)) {
            return ToolCallOutput(errorMessage(root), isError = true)
        }
        val result = root.optJSONObject("result") ?: return ToolCallOutput("", isError = false)
        val isError = result.optBoolean("isError", false)
        val content = result.optJSONArray("content") ?: return ToolCallOutput(
            result.optString("text"),
            isError,
        )
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "image" -> text.append("[image]")
                "resource" -> text.append("[resource]")
                else -> text.append(block.optString("text"))
            }
        }
        return ToolCallOutput(text.toString(), isError)
    }

    /** True when the body is a JSON-RPC error object (rather than a successful result). */
    fun hasError(root: JSONObject): Boolean = root.has("error")

    /** Extracts a JSON-RPC error message, or a generic one. */
    fun errorMessage(root: JSONObject): String {
        val error = root.opt("error") ?: return "JSON-RPC error"
        return when (error) {
            is JSONObject -> error.optString("message").ifBlank { "code ${error.optInt("code")}" }
            is String -> error
            else -> "JSON-RPC error"
        }
    }

    /** Extracts the error message from a raw body, or null when the body is a successful result. */
    fun extractError(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return "Invalid JSON-RPC response"
        return if (hasError(root)) errorMessage(root) else null
    }

    private fun envelope(id: Long): JSONObject = JSONObject()
        .put("jsonrpc", JSONRPC_VERSION)
        .put("id", id)

    /** Parses a JSON-RPC notification (a request without an id), e.g. for stdio log streaming. */
    fun parseNotification(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
        ?.takeIf { it.optString("jsonrpc") == JSONRPC_VERSION && !it.has("id") }
}
