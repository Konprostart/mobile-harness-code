package com.mobileharness.code.mcp

/**
 * Process-scoped cache of discovered MCP tools, keyed by server id. Populated by "Test & List
 * Tools" in Settings and refreshed on demand by the Sidekick engine. Cleared on app restart.
 */
object McpToolRegistry {
    @Volatile
    private var cache: Map<String, List<McpTool>> = emptyMap()

    @Synchronized
    fun put(serverId: String, tools: List<McpTool>) {
        cache = cache + (serverId to tools)
    }

    @Synchronized
    fun get(serverId: String): List<McpTool> = cache[serverId].orEmpty()

    @Synchronized
    fun remove(serverId: String) {
        cache = cache - serverId
    }

    @Synchronized
    fun all(): Map<String, List<McpTool>> = cache

    @Synchronized
    fun clear() {
        cache = emptyMap()
    }
}
