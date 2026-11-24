package com.mobileharness.code.skills

/**
 * Models for the advanced SKILL.md-based skill system, ported and adapted from AndroidHarness 4.0
 * (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * These coexist with the legacy [Skill] prompt-fragment model: the legacy model is a curated block
 * of assistant instructions, while [InstalledSkill] is a full SKILL.md directory (with scripts,
 * references, templates and assets) that can be installed, executed and updated.
 */

/** Where a skill was installed from. */
enum class SkillSource { BUNDLED, USER, PROJECT, PLUGIN }

/** Result of parsing one SKILL.md file. */
data class ParsedSkill(
    val name: String,
    val description: String,
    val category: String,
    val body: String,
    val raw: String,
    /** Every top-level frontmatter field, such as allowed-tools, model or argument-hint. */
    val fields: Map<String, String> = emptyMap(),
) {
    val catalogDescription: String
        get() = if (description.length <= MAX_CATALOG_DESCRIPTION) description
        else description.take(MAX_CATALOG_DESCRIPTION - 3).trimEnd() + "..."

    companion object {
        const val MAX_CATALOG_DESCRIPTION = 80
        const val MAX_NAME = 64
        const val MAX_USER_CHARS = 20_000
        val NAME_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    }
}

class SkillParseException(message: String) : Exception(message)

/** An installed SKILL.md directory, as persisted and shown in the Skills screen. */
data class InstalledSkill(
    /** Stable identifier. For user/project/plugin skills this is the skill name (namespaced `plugin:name`). */
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    /** The SKILL.md body (instructions), with variables already expanded when loaded. */
    val body: String,
    /** The full raw SKILL.md text (unexpanded). */
    val raw: String,
    /** All top-level frontmatter fields. */
    val fields: Map<String, String> = emptyMap(),
    val source: SkillSource,
    /** Origin reference: GitHub URL, local path or zip name. */
    val sourceRef: String,
    /** Directory name inside the skills root (never contains path separators). */
    val relativeDir: String,
    /** Supporting files relative to the skill directory (e.g. `scripts/run.py`). */
    val files: List<String> = emptyList(),
    /** User-managed tags for search/filter. */
    val tags: List<String> = emptyList(),
    /** Frontmatter `version`, or "0.0.0" when absent. */
    val version: String = "0.0.0",
    val enabled: Boolean = true,
    val installedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    companion object {
        val SUPPORT_DIRS = setOf("references", "templates", "scripts", "assets")
        const val MAX_FILES = 300
        const val MAX_TAGS = 12
        const val MAX_TAG_CHARS = 40
    }
}
