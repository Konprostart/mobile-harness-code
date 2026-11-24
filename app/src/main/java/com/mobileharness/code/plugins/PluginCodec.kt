package com.mobileharness.code.plugins

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for [PluginManifest] (and the installed-plugin registry). Decode is fail-safe: a
 * malformed manifest or unknown enum name is skipped/ignored rather than thrown, matching the
 * fail-closed behaviour of the Skill and MCP codecs.
 */
object PluginCodec {

    fun encodeManifest(manifest: PluginManifest): String = JSONObject()
        .put("id", manifest.id)
        .put("name", manifest.name)
        .put("version", manifest.version)
        .put("description", manifest.description)
        .put("entry", manifest.entry)
        .put("permissions", JSONArray().apply { manifest.permissions.forEach { put(it.name) } })
        .put("sha256", manifest.sha256 ?: JSONObject.NULL)
        .put("manifestVersion", manifest.manifestVersion)
        .put("format", manifest.format)
        .put("runtimes", JSONArray().apply { manifest.runtimes.forEach { put(it) } })
        .put("tools", JSONArray().apply {
            manifest.tools.forEach { tool ->
                put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("command", tool.command)
                        .put("inputSchema", tool.inputSchema),
                )
            }
        })
        .toString()

    fun decodeManifest(json: String?): PluginManifest? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        val name = obj.optString("name")
        val version = obj.optString("version")
        if (name.isBlank() || version.isBlank()) return null
        val permissions = mutableListOf<PluginPermission>()
        val arr = obj.optJSONArray("permissions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                runCatching { PluginPermission.valueOf(arr.optString(i)) }.getOrNull()?.let { permissions += it }
            }
        }
        val sha = obj.optString("sha256").takeIf { it.isNotBlank() }
        val tools = mutableListOf<PluginTool>()
        obj.optJSONArray("tools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tool = arr.optJSONObject(i) ?: continue
                val toolName = tool.optString("name")
                if (toolName.isBlank()) continue
                tools += PluginTool(
                    name = toolName,
                    description = tool.optString("description"),
                    command = tool.optString("command"),
                    inputSchema = tool.optJSONObject("inputSchema")?.toString() ?: "{}",
                )
            }
        }
        val runtimes = mutableListOf<String>()
        obj.optJSONArray("runtimes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val value = arr.optString(i)
                if (value.isNotBlank()) runtimes += value
            }
        }
        return PluginManifest(
            id = id,
            name = name,
            version = version,
            description = obj.optString("description"),
            entry = obj.optString("entry"),
            permissions = permissions.distinct(),
            sha256 = sha,
            manifestVersion = obj.optInt("manifestVersion", 1),
            format = obj.optString("format"),
            runtimes = runtimes.distinct(),
            tools = tools,
        )
    }

    fun encodeRegistry(plugins: List<Plugin>): String {
        val array = JSONArray()
        plugins.forEach { p ->
            array.put(
                JSONObject()
                    .put("source", p.source.name)
                    .put("sourceRef", p.sourceRef)
                    .put("installPath", p.installPath)
                    .put("enabled", p.enabled)
                    .put("installedAtMillis", p.installedAtMillis)
                    .put("manifest", JSONObject(encodeManifest(p.manifest))),
            )
        }
        return array.toString()
    }

    fun decodeRegistry(json: String?): List<Plugin> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val plugins = mutableListOf<Plugin>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val manifest = decodeManifest(obj.optJSONObject("manifest")?.toString()) ?: continue
            val source = runCatching { PluginSource.valueOf(obj.optString("source")) }.getOrNull() ?: continue
            val sourceRef = obj.optString("sourceRef")
            if (sourceRef.isBlank()) continue
            plugins += Plugin(
                manifest = manifest,
                source = source,
                sourceRef = sourceRef,
                installPath = obj.optString("installPath"),
                enabled = obj.optBoolean("enabled", true),
                installedAtMillis = obj.optLong("installedAtMillis"),
            )
        }
        return plugins
    }
}
