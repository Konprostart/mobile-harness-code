package com.mobileharness.code.plugins

import java.io.File

/**
 * Detects the runtime environments a plugin bundle needs, ported and adapted from AndroidHarness 4.0
 * (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * The mapping is deterministic: commands map to Debian/Ubuntu package names, and script extensions
 * map to interpreters. It never installs anything — it only reports what a plugin requires so the
 * UI can show missing runtimes and offer installation.
 */
object PluginRuntimeDetection {

    private val byCommand = mapOf(
        "node" to listOf("nodejs"), "npx" to listOf("nodejs", "npm"), "npm" to listOf("nodejs", "npm"),
        "pnpm" to listOf("nodejs", "npm"), "yarn" to listOf("nodejs", "npm"), "tsx" to listOf("nodejs", "npm"),
        "python" to listOf("python"), "python3" to listOf("python"),
        "pip" to listOf("python", "python-pip"), "pip3" to listOf("python", "python-pip"),
        "pipx" to listOf("python", "python-pip"), "uv" to listOf("python", "uv"), "uvx" to listOf("python", "uv"),
        // Deno is an optional direct-download runtime (not an apt package); it is
        // reported by its own name so the UI can show it as the required runtime.
        "deno" to listOf("deno"), "ruby" to listOf("ruby"), "go" to listOf("golang"),
        "java" to listOf("openjdk-17"), "php" to listOf("php"), "perl" to listOf("perl"),
        "git" to listOf("git"), "jq" to listOf("jq"), "curl" to listOf("curl"),
        "rg" to listOf("ripgrep"), "fd" to listOf("fd-find"), "fdfind" to listOf("fd-find"),
        "zip" to listOf("zip"), "unzip" to listOf("unzip"),
    )

    private val byExtension = mapOf(
        "py" to listOf("python"), "js" to listOf("nodejs"), "cjs" to listOf("nodejs"),
        "mjs" to listOf("nodejs"), "ts" to listOf("nodejs", "npm"), "rb" to listOf("ruby"),
        "php" to listOf("php"), "pl" to listOf("perl"),
    )

    /** Linux packages a single command needs, given its first word and any script path. */
    fun forCommand(command: String): List<String> {
        val words = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() && !it.contains('=') }
        val first = words.firstOrNull()?.substringAfterLast('/') ?: return emptyList()
        val direct = byCommand[first].orEmpty()
        // A bare script path (e.g. "scripts/t.py") maps to its interpreter.
        val bareScript = if (direct.isEmpty()) {
            byExtension[first.substringAfterLast('.', "").lowercase()].orEmpty()
        } else {
            emptyList()
        }
        val script = words.drop(1).firstNotNullOfOrNull { word ->
            byExtension[word.substringAfterLast('.', "").lowercase()]
        }.orEmpty()
        return (direct + bareScript + script).distinct()
    }

    /** The runtimes declared by an entry command plus every tool command in the manifest. */
    fun forManifest(manifest: PluginManifest): List<String> {
        val found = linkedSetOf<String>()
        manifest.entry.takeIf { it.isNotBlank() }?.let { found += forCommand(it) }
        manifest.tools.forEach { tool ->
            if (tool.command.isNotBlank()) found += forCommand(tool.command)
        }
        return found.toList()
    }

    /** The runtimes implied by the scripts inside a bundle root (bounded walk). */
    fun forBundle(root: File): List<String> {
        val found = linkedSetOf<String>()
        root.walkTopDown().onEnter { it.name !in PluginArchive.SKIPPED }
            .filter { it.isFile }.take(2000).forEach { file ->
                found += byExtension[file.extension.lowercase()].orEmpty()
            }
        return found.toList()
    }

    /** Whether the script interpreter for [scriptPath] is already known. */
    fun interpreterFor(scriptPath: String): String? {
        val ext = scriptPath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py" -> "python3"
            "js", "cjs", "mjs" -> "node"
            "ts" -> "npx"
            "rb" -> "ruby"
            "php" -> "php"
            "pl" -> "perl"
            else -> null
        }
    }
}
