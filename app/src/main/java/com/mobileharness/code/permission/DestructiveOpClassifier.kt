package com.mobileharness.code.permission

/**
 * Detects irreversible / high-blast-radius operations so the unified permission system can force an
 * explicit user approval for them even under ALLOW_ALL. Conservative by design: when in doubt the
 * operation is treated as destructive, which only makes the gate stricter.
 */
object DestructiveOpClassifier {

    // Anchored on command-like boundaries; only applied to shell commands and operation keys.
    private val DESTRUCTIVE_REGEX = listOf(
        // rm with a force/recursive flag, or rm of an absolute path.
        Regex("""\brm\s+(-\S*\s+)*-[a-zA-Z]*[rf][a-zA-Z]*\b"""),
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*/[^\s]*"""),
        Regex("""\bgit\s+reset\s+--hard\b"""),
        Regex("""\bgit\s+clean\s+-[a-zA-Z]*f"""),
        Regex("""\bgit\s+push\s+--force\b"""),
        Regex("""\bgit\s+push\s+-f\b"""),
        Regex("""\bdrop\s+database\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdrop\s+table\b""", RegexOption.IGNORE_CASE),
        Regex("""\btruncate\s+table\b""", RegexOption.IGNORE_CASE),
        Regex("""\bformat\s+[a-zA-Z0-9._/-]+\s+/"""),
        Regex("""\bmkfs(\.\w+)?\b"""),
        Regex("""\bdd\s+if=.*\bof=""", RegexOption.DOT_MATCHES_ALL),
        Regex("""\bshred\b"""),
        Regex("""\bpm\s+uninstall\b"""),
        Regex("""\brmdir\s+/"""),
        Regex("""\bdel\s+/[a-zA-Z]:\\""", RegexOption.IGNORE_CASE),
    )

    // Operation-key prefixes that are always destructive (used by structured tools).
    private val DESTRUCTIVE_KEYS = setOf(
        "file.delete",
        "file.rmtree",
        "git.reset_hard",
        "git.force_push",
        "git.clean_force",
        "android.uninstall",
        "android.factory_reset",
        "workspace.delete",
        "project.delete",
        "plugin.uninstall",
        "db.drop",
        "db.truncate",
    )

    /** True if the operation key or command text looks destructive. */
    fun isDestructive(operationKey: String?, command: String?): Boolean {
        val key = operationKey?.trim()?.lowercase()
        if (key != null && DESTRUCTIVE_KEYS.any { key == it || key.startsWith("$it.") }) return true
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty()) return false
        return DESTRUCTIVE_REGEX.any { it.containsMatchIn(cmd) }
    }

    /** Risk classification for the approval UI, derived from destructiveness and mutation. */
    fun riskLevel(mutating: Boolean, destructive: Boolean): com.mobileharness.code.model.RiskLevel {
        return when {
            destructive -> com.mobileharness.code.model.RiskLevel.HIGH
            mutating -> com.mobileharness.code.model.RiskLevel.REVIEW
            else -> com.mobileharness.code.model.RiskLevel.SAFE
        }
    }
}
