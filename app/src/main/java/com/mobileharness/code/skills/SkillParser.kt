package com.mobileharness.code.skills

/**
 * Parser for the standard SKILL.md format (YAML frontmatter + Markdown body), ported and adapted
 * from the AndroidHarness 4.0 project (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * A skill file looks like:
 * ```
 * ---
 * name: my-skill
 * description: What it does
 * category: code
 * version: 1.0.0
 * ---
 * # Instructions
 * ...
 * ```
 *
 * The parser is pure JVM (no Android imports) and fail-closed: a skill that is missing its
 * required fields, carries path separators in its name, or exceeds size bounds is rejected with
 * [SkillParseException] rather than partially accepted.
 */
object SkillParser {

    fun parse(raw: String): ParsedSkill {
        val text = raw.replace("\r\n", "\n").trimStart('\uFEFF')
        if (!text.startsWith("---")) {
            throw SkillParseException("Skill must start with YAML frontmatter (---).")
        }
        val rest = text.removePrefix("---")
        val close = rest.indexOf("\n---")
        if (close < 0) {
            throw SkillParseException("Frontmatter is not closed with ---.")
        }
        val yaml = rest.substring(0, close).trim()
        val body = rest.substring(close + 4).trim()
        if (body.isEmpty()) {
            throw SkillParseException("Skill body is empty.")
        }

        val fields = parseSimpleYaml(yaml)
        val name = fields["name"]?.trim().orEmpty()
        val description = fields["description"]?.trim().orEmpty()
        val category = fields["category"]?.trim()?.ifBlank { null } ?: "general"

        if (name.isEmpty()) throw SkillParseException("Missing required field: name.")
        if (".." in name || name.contains('/') || name.contains('\\')) {
            throw SkillParseException("Skill name cannot contain path separators or ..")
        }
        if (!ParsedSkill.NAME_REGEX.matches(name) || name.length > ParsedSkill.MAX_NAME) {
            throw SkillParseException(
                "Skill name must be lowercase letters, digits, and hyphens (max ${ParsedSkill.MAX_NAME}).",
            )
        }
        if (description.isEmpty()) throw SkillParseException("Missing required field: description.")

        return ParsedSkill(
            name = name,
            description = description,
            category = category.ifBlank { "general" },
            body = body,
            raw = text.trimEnd() + "\n",
            fields = fields,
        )
    }

    /**
     * Lenient parse for a skill written for another harness. A missing or invalid name falls back
     * to [folderName], a missing description to the first body paragraph, and the other frontmatter
     * fields are kept. Returns the corrected [ParsedSkill] without touching the source file.
     */
    fun lenient(raw: String, folderName: String): ParsedSkill {
        runCatching { return parse(raw) }
        val text = raw.replace("\r\n", "\n").trimStart('\uFEFF')
        var front = ""
        var body = text
        if (text.startsWith("---")) {
            val rest = text.removePrefix("---")
            val close = rest.indexOf("\n---")
            if (close >= 0) {
                front = rest.substring(0, close)
                body = rest.substring(close + 4).substringAfter('\n', "")
            }
        }
        val fields = parseSimpleYaml(front)
        val name = safeName(fields["name"]?.takeIf { NAME_SAFE.matches(it) } ?: fields["name"] ?: folderName)
        val paragraph = body.lines().map { it.trim() }
            .dropWhile { it.isEmpty() || it.startsWith("#") || it.startsWith("<") }
            .takeWhile { it.isNotEmpty() }.joinToString(" ").take(1000)
        val description = (fields["description"] ?: paragraph).trim().ifBlank { "Instructions from $folderName." }
        val content = body.trim().ifBlank { description }
        val kept = fields.filterKeys { it !in setOf("name", "description") }
        val normalized = buildString {
            append("---\nname: ").append(name).append('\n')
            append("description: ").append(quoted(description)).append('\n')
            kept.forEach { (key, value) ->
                if (Regex("[A-Za-z0-9_-]+").matches(key)) {
                    append(key).append(": ").append(quoted(value)).append('\n')
                }
            }
            append("---\n\n").append(content).append('\n')
        }
        return parse(normalized)
    }

    private val NAME_SAFE = Regex("[a-z0-9][a-z0-9-]{0,63}")

    /** Lowercase letters, digits and hyphens, as skill names require. */
    fun safeName(raw: String): String = raw.substringAfterLast('/').lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .take(ParsedSkill.MAX_NAME).trim('-').ifBlank { "skill" }

    fun validateUserContent(raw: String): ParsedSkill {
        if (raw.length > ParsedSkill.MAX_USER_CHARS) {
            throw SkillParseException("Skill is too long (max ${ParsedSkill.MAX_USER_CHARS} characters).")
        }
        return parse(raw)
    }

    /**
     * Tiny YAML mapping parser for the top-level scalar fields skills use. Handles `key: value`,
     * quoted values, block scalars (`|`, `>`, with chomping marks) and plain values continued on
     * more-indented lines. Nested maps and lists are ignored.
     */
    fun parseSimpleYaml(yaml: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val lines = yaml.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i++]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-") || line.first().isWhitespace()) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim()
            var value = trimmed.substring(colon + 1).trim()
            val continuation = mutableListOf<String>()
            while (i < lines.size && (lines[i].isBlank() || lines[i].first().isWhitespace())) continuation += lines[i++]
            val block = Regex("^([|>])[+-]?\\d?$").find(value)
            if (block != null) {
                val body = continuation.dropLastWhile { it.isBlank() }
                val indent = body.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
                val text = body.map { if (it.isBlank()) "" else it.drop(indent) }
                value = if (block.groupValues[1] == "|") text.joinToString("\n")
                else text.joinToString("\n").split("\n\n").joinToString("\n") { it.replace('\n', ' ') }
            } else if (continuation.any { it.isNotBlank() } && value.isNotEmpty() && value.first() !in "[{") {
                value = (listOf(value) + continuation.map { it.trim() }.filter { it.isNotEmpty() }).joinToString(" ")
            }
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\n", " ")
            } else if (value.startsWith("'") && value.endsWith("'") && value.length >= 2) {
                value = value.substring(1, value.length - 1).replace("''", "'")
            }
            if (key.isNotEmpty() && value.isNotEmpty() && key !in out) out[key] = value.trim()
        }
        return out
    }

    private fun quoted(value: String) =
        "\"" + value.replace("\"", "\\\"").replace(Regex("\\s+"), " ").trim() + "\""
}
