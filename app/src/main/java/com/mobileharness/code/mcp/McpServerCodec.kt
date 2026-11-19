package com.mobileharness.code.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON serialization for the registered MCP servers. Extracted from
 * [com.mobileharness.code.data.AppPreferences] so the round-trip is unit-testable without an
 * Android context. Malformed entries and unknown enum values are dropped (fail-closed) rather than
 * throwing.
 */
object McpServerCodec {
    fun toJson(servers: List<McpServerConfig>): String {
        val arr = JSONArray()
        servers.forEach { server ->
            arr.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("transport", server.transport.name)
                    .put("url", server.url)
                    .put("command", server.command)
                    .put("bearer_token", server.bearerToken)
                    .put("confirm_mode", server.confirmMode.name)
                    .put("enabled", server.enabled),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<McpServerConfig> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (index in 0 until arr.length()) {
                    val obj = runCatching { arr.getJSONObject(index) }.getOrNull() ?: continue
                    val config = parseServer(obj) ?: continue
                    add(config)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseServer(obj: JSONObject): McpServerConfig? {
        val id = obj.optString("id").ifBlank { return null }
        return McpServerConfig(
            id = id,
            name = obj.optString("name"),
            transport = enumOrDefault(obj.optString("transport"), McpTransport.HTTP),
            url = obj.optString("url"),
            command = obj.optString("command"),
            bearerToken = obj.optString("bearer_token"),
            confirmMode = enumOrDefault(obj.optString("confirm_mode"), McpToolConfirmMode.MUTATING_ONLY),
            enabled = obj.optBoolean("enabled", true),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}
