package com.mobileharness.code.skills

/**
 * Skill variable expansion and slash-command templates, ported and adapted from AndroidHarness 4.0
 * (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * Skills written for Claude Code name their own folder with ${CLAUDE_SKILL_DIR}, their plugin with
 * ${CLAUDE_PLUGIN_ROOT} and the open project with ${CLAUDE_PROJECT_DIR}. Expanding them to real
 * paths when a skill is loaded lets the original SKILL.md run unedited. A variable with no known
 * value stays as written.
 */
object SkillVariables {
    private const val NAMES = "CLAUDE_SKILL_DIR|CLAUDE_PLUGIN_ROOT|CLAUDE_PROJECT_DIR"
    private val variable = Regex("\\$(?:\\{($NAMES)\\}|($NAMES)(?![A-Za-z0-9_]))")

    fun expand(text: String, skillDir: String?, pluginRoot: String?, projectDir: String?): String {
        if ('$' !in text) return text
        return variable.replace(text) { match ->
            val value = when (match.groupValues[1].ifEmpty { match.groupValues[2] }) {
                "CLAUDE_SKILL_DIR" -> skillDir
                "CLAUDE_PLUGIN_ROOT" -> pluginRoot
                else -> projectDir
            }
            value ?: match.value
        }
    }
}

/**
 * Slash commands written for Claude Code and Gemini CLI. Arguments are filled in exactly, and shell
 * output and file contents are put in place before the model reads the command, as those harnesses do.
 *
 * Claude Code: `$ARGUMENTS`, `$1` to `$9`, ``!`command` `` , `@path`. Gemini CLI: `{{args}}`,
 * `!{command}`, `@{path}`.
 */
object CommandTemplate {
    private val positional = Regex("\\$([1-9])(?![0-9])")
    private val claudeShell = Regex("!`([^`\\n]+)`")
    private val geminiShell = Regex("!\\{([^}\\n]+)\\}")
    private val geminiFile = Regex("@\\{([^}\\n]+)\\}")
    private val claudeFile = Regex("(?<![\\w@/.`])@((?:\\.{0,2}/)?[A-Za-z0-9_][A-Za-z0-9_./-]*[A-Za-z0-9_/])")

    /** True when [body] needs filling in: it takes arguments, runs commands or reads files. */
    fun applies(fields: Map<String, String>, body: String): Boolean =
        fields["command"] == "true" || "\$ARGUMENTS" in body || "{{args}}" in body ||
            claudeShell.containsMatchIn(body) || geminiShell.containsMatchIn(body)

    /** Splits a raw argument string into words, honoring single/double quotes. */
    fun words(arguments: String): List<String> {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        arguments.forEach { c ->
            when {
                quote != null && c == quote -> quote = null
                quote != null -> word.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> { if (word.isNotEmpty()) out += word.toString(); word.clear() }
                else -> word.append(c)
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    /** [body] with the arguments in place. */
    fun arguments(body: String, arguments: String): String {
        val args = arguments.trim()
        val list = words(args)
        val placeholders = "\$ARGUMENTS" in body || "{{args}}" in body || positional.containsMatchIn(body)
        val filled = positional.replace(
            body.replace("\$ARGUMENTS", args).replace("{{args}}", args),
        ) { list.getOrNull(it.groupValues[1].toInt() - 1).orEmpty() }
        return if (placeholders || args.isEmpty()) filled else "$filled\n\nARGUMENTS: $args"
    }

    /** The shell commands the text runs before it is sent, in order. */
    fun shellCommands(text: String): List<String> =
        (claudeShell.findAll(text).map { it.groupValues[1] } + geminiShell.findAll(text).map { it.groupValues[1] })
            .map(String::trim).distinct().toList()

    fun withShellOutput(text: String, output: (String) -> String): String {
        val first = claudeShell.replace(text) { output(it.groupValues[1].trim()) }
        return geminiShell.replace(first) { output(it.groupValues[1].trim()) }
    }

    /** Paths the text names with @path or @{path}; only relative paths without `..` are returned. */
    fun fileReferences(text: String): List<String> =
        (geminiFile.findAll(text).map { it.groupValues[1].trim() } + claudeFile.findAll(text).map { it.groupValues[1] })
            .filter { it.isNotBlank() && !it.startsWith("/") && it.split('/').none { part -> part == ".." } }
            .distinct().toList()

    /** Appends the contents of each file that was read, keyed by the path the text used. */
    fun withFiles(text: String, contents: Map<String, String>): String {
        if (contents.isEmpty()) return text
        val inlined = geminiFile.replace(text) { match ->
            contents[match.groupValues[1].trim()]?.let { "\n```\n$it\n```\n" } ?: match.value
        }
        val rest = contents.filterKeys { path -> geminiFile.findAll(text).none { it.groupValues[1].trim() == path } }
        if (rest.isEmpty()) return inlined
        return inlined + "\n\n" + rest.entries.joinToString("\n\n") { (path, body) -> "Contents of $path:\n```\n$body\n```" }
    }

    /** Whether [allowedTools] (a command's allowed-tools field) lets it run [command] without asking. */
    fun shellAllowed(allowedTools: String?, command: String): Boolean {
        val rules = allowedTools.orEmpty().split(Regex(",(?![^(]*\\))")).map(String::trim).filter(String::isNotEmpty)
        return rules.any { rule ->
            if (rule == "Bash" || rule == "Bash(*)" || rule == "Bash(:*)") return@any true
            val pattern = Regex("^Bash\\((.*)\\)$").find(rule)?.groupValues?.get(1) ?: return@any false
            val prefix = pattern.removeSuffix(":*").removeSuffix("*").trim()
            if (pattern.endsWith("*")) command.trim().startsWith(prefix) else command.trim() == prefix
        }
    }
}
