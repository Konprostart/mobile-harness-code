package com.mobileharness.code.mcp

/** A single MCP tool-call audit entry, shown in Developer Tools. Never contains credentials. */
data class McpToolLogEntry(
    val timestampMillis: Long,
    val serverName: String,
    val toolName: String,
    val stage: McpToolStage,
    val detail: String? = null,
)

enum class McpToolStage { LIST, CALL, BLOCKED, NEEDS_CONFIRMATION, ERROR, SUCCESS }

/**
 * In-memory ring buffer of MCP tool events, mirrored from [com.mobileharness.code.network.RouteLog]
 * so the Sidekick assistant and Developer Tools share one source of truth. Process-scoped and
 * cleared on restart; entries never include API keys or bearer tokens.
 */
object McpToolLog {
    private const val MAX_ENTRIES = 100
    private val entries = ArrayDeque<McpToolLogEntry>()

    @Synchronized
    fun record(entry: McpToolLogEntry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<McpToolLogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
