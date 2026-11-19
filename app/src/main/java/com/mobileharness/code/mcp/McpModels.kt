package com.mobileharness.code.mcp

/**
 * Transport used to reach an MCP server.
 *
 *  - [HTTP]: Streamable HTTP / SSE-JSON server (local, LAN or remote over https).
 *  - [STDIO]: a local process launched on the device (PRoot/Termux), spoken to over
 *    newline-delimited JSON-RPC on stdin/stdout.
 */
enum class McpTransport { HTTP, STDIO }

/**
 * When a discovered MCP tool requires user confirmation before it is executed on the device.
 *
 *  - [ALWAYS_CONFIRM]: strictest — every tool call is held for confirmation.
 *  - [MUTATING_ONLY]: read-only tools run; tools the guard flags as mutating are blocked.
 *  - [NEVER]: expert override — no confirmation gate (documented as a trust decision in the UI).
 */
enum class McpToolConfirmMode { ALWAYS_CONFIRM, MUTATING_ONLY, NEVER }

/**
 * A single registered MCP server. [bearerToken] is an optional, *server-specific* token the user
 * entered for this server only — it is never taken from [com.mobileharness.code.data.ApiKeyVault]
 * and never shared with another server.
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val url: String = "",
    val command: String = "",
    val bearerToken: String = "",
    val confirmMode: McpToolConfirmMode = McpToolConfirmMode.MUTATING_ONLY,
    val enabled: Boolean = true,
) {
    /** Whether the config is complete enough to attempt a connection. */
    fun isComplete(): Boolean = name.isNotBlank() && when (transport) {
        McpTransport.HTTP -> url.isNotBlank()
        McpTransport.STDIO -> command.isNotBlank()
    }
}

/** A discovered MCP tool: a name, a human description, and the raw JSON-Schema of its arguments. */
data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: String = "{}",
)

/** Outcome of listing tools for one server. */
sealed interface McpListResult {
    data class Success(val tools: List<McpTool>) : McpListResult
    data class Failure(val message: String) : McpListResult
}

/** Why a tool call did not execute. */
enum class McpCallFailureKind { TOOL_NOT_FOUND, GUARD_BLOCKED, NETWORK, AUTH, TIMEOUT, PROTOCOL, OTHER }

/** Outcome of invoking one MCP tool. */
sealed interface McpCallResult {
    data class Success(val text: String, val isError: Boolean = false) : McpCallResult
    data class Failure(
        val message: String,
        val kind: McpCallFailureKind = McpCallFailureKind.OTHER,
    ) : McpCallResult
}
