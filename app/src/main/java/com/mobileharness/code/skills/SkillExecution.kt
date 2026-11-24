package com.mobileharness.code.skills

/**
 * Skill execution engine: turns an installed [InstalledSkill] plus user arguments into the
 * instruction block an agent receives, and decides which embedded shell commands / file reads are
 * permitted. Ported and adapted from AndroidHarness 4.0 (https://github.com/Sanuu7/AndroidHarness),
 * MIT License.
 *
 * This object is pure JVM logic: it does not run any process or touch the network itself. The
 * actual shell/file side effects are executed by the guest runtime, which this plan feeds and which
 * is gated by the existing [com.mobileharness.code.permission] / privilege policy.
 */
object SkillExecution {

    /** Renders a skill body for the chosen agent: expands arguments and host variables. */
    fun renderBody(skill: InstalledSkill, arguments: String, projectDir: String? = null): String {
        val expanded = SkillVariables.expand(skill.body, skill.relativeDir, null, projectDir)
        return CommandTemplate.arguments(expanded, arguments)
    }

    /** Full instruction block for an agent (used by Sidekick / Claude Code prompts). */
    fun buildInstruction(skill: InstalledSkill, arguments: String, projectDir: String? = null): String {
        val body = renderBody(skill, arguments, projectDir)
        return buildString {
            append("[SKILL: ").append(skill.name).append("]\n")
            append(skill.description).append("\n\n")
            append(body)
        }
    }

    data class ShellPlan(val all: List<String>, val allowed: List<String>, val blocked: List<String>)

    /**
     * Splits the shell commands a skill body embeds into allowed vs. blocked. When [canExec] is
     * false, every command is blocked. Otherwise a command is allowed only when the skill's own
     * `allowed-tools` field permits it (a skill with no `allowed-tools` allows nothing automatically,
     * matching the conservative fail-closed default).
     */
    fun shellPlan(skill: InstalledSkill, canExec: Boolean): ShellPlan {
        val commands = CommandTemplate.shellCommands(skill.body)
        if (!canExec) return ShellPlan(commands, emptyList(), commands)
        val allowedTools = skill.fields["allowed-tools"]
        val allowed = commands.filter { CommandTemplate.shellAllowed(allowedTools, it) }
        val blocked = commands.filterNot { it in allowed }
        return ShellPlan(commands, allowed, blocked)
    }

    /** Paths a skill body names with @path / @{path}, used to decide which support files to read. */
    fun fileReferences(skill: InstalledSkill): List<String> = CommandTemplate.fileReferences(skill.body)

    /** Whether this skill is a slash command that takes arguments or runs commands. */
    fun isCommand(skill: InstalledSkill): Boolean = CommandTemplate.applies(skill.fields, skill.body)
}
