package com.mobileharness.code.skills

/**
 * Pure, fail-closed validation for skills. A skill is only ever injected into the assistant prompt
 * after it passes these checks, so a malformed or oversized skill can never produce a malformed
 * prompt or flood the model context. All limits are hard and independent of Eco Mode.
 */
object SkillPolicy {
    const val MAX_SKILLS = 64
    const val MAX_NAME_CHARS = 80
    const val MAX_DESCRIPTION_CHARS = 300
    const val MAX_INSTRUCTIONS_CHARS = 8_000

    /** True when the skill is acceptable to store and (if enabled) to inject. */
    fun isValid(skill: Skill): Boolean {
        if (skill.id.isBlank()) return false
        if (containsControl(skill.id) || containsControl(skill.name)) return false
        // Instructions may span multiple lines, so newlines/tabs are allowed; all other control
        // characters (NUL, escape, etc.) are rejected.
        if (containsStrictControl(skill.instructions)) return false
        if (skill.name.isBlank() || skill.name.length > MAX_NAME_CHARS) return false
        if (skill.description.length > MAX_DESCRIPTION_CHARS) return false
        if (skill.instructions.length > MAX_INSTRUCTIONS_CHARS) return false
        return true
    }

    /** Sanitizes a stored skill into a safe, bounded value for prompt injection. */
    fun sanitize(skill: Skill): Skill = Skill(
        id = skill.id.trim().take(120),
        name = skill.name.trim().take(MAX_NAME_CHARS),
        description = skill.description.trim().take(MAX_DESCRIPTION_CHARS),
        instructions = skill.instructions.trim().take(MAX_INSTRUCTIONS_CHARS),
        enabled = skill.enabled,
    )

    /** Renders the enabled skills into a bounded system-prompt fragment (or blank when none). */
    fun buildInstructionBlock(skills: List<Skill>): String {
        val enabled = skills.filter { it.enabled && isValid(it) }.map(::sanitize)
        if (enabled.isEmpty()) return ""
        return buildString {
            append("\n\nYou have these opt-in skills available. Follow them when relevant to the request:")
            enabled.forEach { skill ->
                append("\n\n## Skill: ").append(skill.name).append('\n')
                append(skill.instructions)
            }
        }
    }

    private fun containsControl(value: String): Boolean = value.any { it.code < 0x20 }

    private fun containsStrictControl(value: String): Boolean =
        value.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }
}
