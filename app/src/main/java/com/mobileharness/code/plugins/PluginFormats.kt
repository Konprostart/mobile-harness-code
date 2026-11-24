package com.mobileharness.code.plugins

import com.mobileharness.code.mcp.McpServerConfig
import com.mobileharness.code.mcp.McpToolConfirmMode
import com.mobileharness.code.mcp.McpTransport
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts foreign plugin/extension layouts into the internal Mobile Harness plugin format, ported
 * and adapted from AndroidHarness 4.0 (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * Understood formats:
 *  - Native `plugin.json` (AndroidHarness / Mobile Harness)
 *  - Claude Code plugins (`.claude-plugin/plugin.json`)
 *  - Codex plugins (`.codex-plugin/plugin.json`)
 *  - Cursor plugins (`.cursor-plugin/plugin.json`)
 *  - Gemini CLI extensions (`gemini-extension.json`)
 *  - Generic skill collections (any bundle of SKILL.md folders + rule files)
 *
 * The adapter only *reads and converts*: it never runs a script and never mutates the source. A
 * bundle whose functions cannot be executed by the internal format is reported honestly via
 * [AdaptedPlugin.warnings] rather than being silently claimed as compatible.
 */
object PluginFormats {

    data class AdaptedPlugin(
        val manifest: PluginManifest,
        /** Bundle-relative directories that contain a SKILL.md. */
        val skillDirs: List<String>,
        /** Bundle-relative instruction files. */
        val instructionFiles: List<String>,
        /** Converted MCP servers declared by the bundle. */
        val mcpServers: List<McpServerConfig>,
        val warnings: List<String>,
    )

    private val manifestDirs = listOf(
        ".claude-plugin" to "Claude Code plugin",
        ".codex-plugin" to "Codex plugin",
        ".cursor-plugin" to "Cursor plugin",
    )

    fun adapt(root: File, fallbackName: String = "plugin"): AdaptedPlugin {
        val located = locate(root)
        val warnings = mutableListOf<String>()

        // Native Mobile Harness / AndroidHarness manifest.
        val native = readJson(File(located, "plugin.json"))
        if (native != null && isNative(native)) {
            return adaptNative(located, native, warnings)
        }

        // Claude Code / Codex / Cursor plugin manifests.
        for ((dir, label) in manifestDirs) {
            val manifest = readJson(File(located, "$dir/plugin.json"))
            if (manifest != null) {
                return adaptClaude(located, manifest, label, warnings)
            }
        }

        // Gemini CLI extension.
        val gemini = readJson(File(located, "gemini-extension.json"))
        if (gemini != null) {
            return adaptGemini(located, gemini, warnings)
        }

        // Generic: any bundle of SKILL.md folders and rule files.
        return adaptGeneric(located, fallbackName, warnings)
    }

    private fun adaptNative(root: File, m: JSONObject, warnings: List<String>): AdaptedPlugin {
        val name = m.optString("name").ifBlank { sanitize(root.name) }
        val version = m.optString("version").ifBlank { "0.0.0" }
        val skillDirs = stringList(m.optJSONArray("skills"))
        val instructionFiles = stringList(m.optJSONArray("instructions"))
        val mcpServers = mcpServers(m.optJSONObject("mcp"))
        val tools = tools(m.optJSONArray("tools"))
        val entry = m.optString("entry")
        val permissions = permissions(skillDirs, mcpServers, tools, entry, m.optJSONArray("permissions"))
        val manifest = PluginManifest(
            id = sanitize(name),
            name = name.take(PluginPolicy.MAX_NAME_CHARS),
            version = version.take(64),
            description = m.optString("description").take(PluginPolicy.MAX_DESCRIPTION_CHARS),
            entry = entry.take(PluginPolicy.MAX_ENTRY_CHARS),
            permissions = permissions,
            format = "AndroidHarness",
            runtimes = runtimes(entry, tools),
            tools = tools,
        )
        return AdaptedPlugin(manifest, skillDirs, instructionFiles, mcpServers, warnings)
    }

    private fun adaptClaude(root: File, m: JSONObject, label: String, warnings: List<String>): AdaptedPlugin {
        val name = m.optString("name").ifBlank { sanitize(root.name) }
        val version = m.optString("version").ifBlank { "0.0.0" }

        // skills: explicit dirs, then the conventional skills/ folder.
        var skillDirs = stringList(m.optJSONArray("skills"))
        if (skillDirs.isEmpty() && File(root, "skills").isDirectory) skillDirs = listOf("skills")

        // commands become command-skills; agents become instructions.
        val commandDirs = stringList(m.optJSONArray("commands"))
        val agentFiles = stringList(m.optJSONArray("agents"))
        val instructionFiles = agentFiles.toMutableList()
        val extraWarnings = warnings.toMutableList()

        val mcpServers = mcpServers(m.optJSONObject("mcpServers"))

        val tools = mutableListOf<PluginTool>()
        val entry = m.optString("entry")

        // A command folder becomes tool entries when each command is a file with a name.
        commandDirs.forEach { dir ->
            val full = File(root, dir)
            if (full.isDirectory) {
                full.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    tools += PluginTool(
                        name = sanitize(file.nameWithoutExtension),
                        description = "Slash command from $label",
                        command = rel,
                    )
                }
            }
        }

        val permissions = permissions(skillDirs, mcpServers, tools, entry, null)
        val manifest = PluginManifest(
            id = sanitize(name),
            name = name.take(PluginPolicy.MAX_NAME_CHARS),
            version = version.take(64),
            description = m.optString("description").take(PluginPolicy.MAX_DESCRIPTION_CHARS),
            entry = entry.take(PluginPolicy.MAX_ENTRY_CHARS),
            permissions = permissions,
            format = label,
            runtimes = runtimes(entry, tools),
            tools = tools,
        )
        if (m.has("hooks")) {
            extraWarnings += "Claude Code hooks are not executed by Mobile Harness (they are shown but not run)."
        }
        return AdaptedPlugin(manifest, skillDirs, instructionFiles.distinct(), mcpServers, extraWarnings.distinct())
    }

    private fun adaptGemini(root: File, m: JSONObject, warnings: List<String>): AdaptedPlugin {
        val name = m.optString("name").ifBlank { sanitize(root.name) }
        val mcpServers = mcpServers(m.optJSONObject("mcpServers"))
        val skillDirs = if (File(root, "skills").isDirectory) listOf("skills") else emptyList()
        val contextFiles = stringList(m.optJSONArray("contextFileName")).ifEmpty { listOf("GEMINI.md") }
            .filter { File(root, it).isFile }
        val manifest = PluginManifest(
            id = sanitize(name),
            name = name.take(PluginPolicy.MAX_NAME_CHARS),
            version = m.optString("version").ifBlank { "0.0.0" }.take(64),
            description = m.optString("description").take(PluginPolicy.MAX_DESCRIPTION_CHARS),
            permissions = permissions(skillDirs, mcpServers, emptyList(), "", null),
            format = "Gemini CLI extension",
            runtimes = runtimes("", emptyList()),
            tools = emptyList(),
        )
        return AdaptedPlugin(manifest, skillDirs, contextFiles, mcpServers, warnings)
    }

    private fun adaptGeneric(root: File, fallbackName: String, warnings: List<String>): AdaptedPlugin {
        val skillDirs = findSkillDirs(root).map { it.relativeTo(root).invariantSeparatorsPath }
        val ruleFiles = listOf("AGENTS.md", "CLAUDE.md", "GEMINI.md", ".cursorrules")
            .map(root::resolve).filter { it.isFile }.map { it.name }
        val manifest = PluginManifest(
            id = sanitize(fallbackName),
            name = sanitize(fallbackName),
            version = "0.0.0",
            description = "Imported skill collection (${skillDirs.size} skills).",
            permissions = if (skillDirs.isNotEmpty()) listOf(PluginPermission.FILESYSTEM_READ) else emptyList(),
            format = "Skill collection",
            runtimes = runtimes("", emptyList()),
            tools = emptyList(),
        )
        return AdaptedPlugin(manifest, skillDirs, ruleFiles, emptyList(), warnings)
    }

    // --- helpers -------------------------------------------------------------------------

    private fun isNative(m: JSONObject): Boolean =
        m.keys().asSequence().none { it in setOf("commands", "agents", "mcpServers", "outputStyles", "lspServers") } &&
            listOf("skills", "instructions", "mcp", "hooks", "tools", "entry").any { m.has(it) }

    private fun locate(root: File): File {
        var current = root.canonicalFile
        fun recognizable(dir: File) = dir.resolve("plugin.json").isFile || dir.resolve("SKILL.md").isFile ||
            dir.resolve("package.json").isFile || dir.resolve("gemini-extension.json").isFile ||
            manifestDirs.any { dir.resolve(it.first).isDirectory } ||
            dir.listFiles().orEmpty().any { it.isDirectory && it.resolve("SKILL.md").isFile }
        repeat(4) {
            if (recognizable(current)) return@repeat
            val children = current.listFiles().orEmpty().filterNot { it.name in PluginArchive.SKIPPED }
            if (children.size == 1 && children.single().isDirectory) current = children.single()
        }
        return current
    }

    private fun findSkillDirs(root: File): List<File> {
        if (File(root, "SKILL.md").isFile) return listOf(root)
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in PluginArchive.SKIPPED }
            .flatMap { findSkillDirs(it) }
            .sortedBy { it.name }
    }

    private fun readJson(file: File): JSONObject? = runCatching {
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return null
        JSONObject(file.readText())
    }.getOrNull()

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotBlank()) add(v)
            }
        }
    }

    private fun mcpServers(obj: JSONObject?): List<McpServerConfig> {
        if (obj == null) return emptyList()
        val out = mutableListOf<McpServerConfig>()
        obj.keys().forEach { key ->
            val server = obj.optJSONObject(key) ?: return@forEach
            val url = server.optString("url").ifBlank { server.optString("httpUrl").ifBlank { server.optString("serverUrl") } }
            val command = server.optString("command")
            val args = stringList(server.optJSONArray("args")).joinToString(" ")
            val id = sanitize(key).ifBlank { "server-${out.size}" }
            if (url.isNotBlank()) {
                out += McpServerConfig(
                    id = id, name = key, transport = McpTransport.HTTP, url = url,
                    confirmMode = McpToolConfirmMode.MUTATING_ONLY, enabled = true,
                )
            } else if (command.isNotBlank()) {
                val full = if (args.isBlank()) command else "$command $args"
                out += McpServerConfig(
                    id = id, name = key, transport = McpTransport.STDIO, command = full,
                    confirmMode = McpToolConfirmMode.MUTATING_ONLY, enabled = true,
                )
            }
        }
        return out
    }

    private fun tools(arr: JSONArray?): List<PluginTool> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                add(
                    PluginTool(
                        name = name,
                        description = obj.optString("description"),
                        command = obj.optString("command"),
                        inputSchema = obj.optJSONObject("inputSchema")?.toString() ?: "{}",
                    ),
                )
            }
        }
    }

    private fun permissions(
        skillDirs: List<String>,
        mcpServers: List<McpServerConfig>,
        tools: List<PluginTool>,
        entry: String,
        declared: JSONArray?,
    ): List<PluginPermission> {
        val out = linkedSetOf<PluginPermission>()
        if (declared != null) {
            for (i in 0 until declared.length()) {
                runCatching { PluginPermission.valueOf(declared.optString(i)) }.getOrNull()?.let { out += it }
            }
        }
        if (skillDirs.isNotEmpty()) { out += PluginPermission.FILESYSTEM_READ; out += PluginPermission.FILESYSTEM_WRITE }
        if (mcpServers.isNotEmpty()) out += PluginPermission.NETWORK
        if (tools.isNotEmpty() || entry.isNotBlank()) out += PluginPermission.EXEC
        return out.toList()
    }

    private fun runtimes(entry: String, tools: List<PluginTool>): List<String> {
        val found = linkedSetOf<String>()
        entry.takeIf { it.isNotBlank() }?.let { found += PluginRuntimeDetection.forCommand(it) }
        tools.forEach { if (it.command.isNotBlank()) found += PluginRuntimeDetection.forCommand(it.command) }
        return found.toList()
    }

    /** Lowercase letters, digits and hyphens, up to 120 chars. */
    private fun sanitize(raw: String, fallback: String = "plugin"): String {
        val cleaned = raw.substringAfterLast('/').lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(120).trim('-')
        return cleaned.ifBlank { fallback }
    }
}
