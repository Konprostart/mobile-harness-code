package com.mobileharness.code.hooks

import com.mobileharness.code.permission.PermissionDecision
import com.mobileharness.code.permission.PermissionPolicy
import com.mobileharness.code.permission.PermissionRequest

/**
 * Advanced Agent Hook engine, primarily for the in-app Sidekick assistant (the one agent whose tool
 * loop this app fully controls). It is a pure, fail-closed dispatch layer: declarative [HookRule]s
 * are matched against an [HookEvent] and produce a combined [HookResult] whose action is always the
 * *most restrictive* of ALLOW < ASK < REWRITE < CONTINUE < BLOCK/STOP, so no single permissive hook
 * can override a blocking one. A hook can never bypass the central [PermissionPolicy]: the engine
 * re-checks destructive/secret/out-of-scope requests through the policy before honoring any allow.
 *
 * For Claude Code, DeepSeek Harness and Antigravity this app does NOT take over their native hook
 * systems; those CLIs already execute their own hooks inside the PRoot runtime. This engine exists
 * so Sidekick can honor the same event vocabulary and give the user a central hook configuration.
 */

enum class HookEvent {
    PRE_TOOL_USE,
    POST_TOOL_USE,
    POST_TOOL_USE_FAILURE,
    PERMISSION_REQUEST,
    STOP,
    SESSION_START,
    SESSION_END,
    PRE_COMPACT,
    SUBAGENT_START,
    SUBAGENT_STOP,
    ;

    companion object {
        /** External (Claude Code / Gemini) event names that map onto this app's vocabulary. */
        fun fromExternal(name: String): HookEvent? = when (name) {
            "PreToolUse", "BeforeTool" -> PRE_TOOL_USE
            "PostToolUse", "AfterTool" -> POST_TOOL_USE
            "PostToolUseFailure" -> POST_TOOL_USE_FAILURE
            "PermissionRequest" -> PERMISSION_REQUEST
            "Stop" -> STOP
            "SessionStart" -> SESSION_START
            "SessionEnd" -> SESSION_END
            "PreCompact", "PreCompress" -> PRE_COMPACT
            "SubagentStart" -> SUBAGENT_START
            "SubagentStop" -> SUBAGENT_STOP
            else -> null
        }
    }
}

/** What a hook may demand of the caller. Ordered by restrictiveness (see [HookResult.action]). */
enum class HookAction {
    ALLOW,
    ASK,
    REWRITE,
    CONTINUE,
    BLOCK,
    STOP,
}

/** A declarative hook rule registered by the user (or a plugin adapter). */
data class HookRule(
    val id: String,
    val event: HookEvent,
    /** Tool-name matcher: "*" (any), a literal name, or a regex `name|other`. */
    val matcher: String = "*",
    val action: HookAction,
    val message: String = "",
    val enabled: Boolean = true,
) {
    fun matches(event: HookEvent, toolName: String): Boolean {
        if (!enabled || this.event != event) return false
        if (matcher.isBlank() || matcher == "*") return true
        val pattern = runCatching { Regex("^(?:$matcher)$") }.getOrNull()
        return if (pattern != null) pattern.matches(toolName) else toolName == matcher
    }
}

/** The combined outcome of every hook that fired for an event. */
data class HookResult(
    val action: HookAction = HookAction.ALLOW,
    val message: String = "",
    val rewrittenArguments: String? = null,
    val auditEntries: List<String> = emptyList(),
) {
    /** The most restrictive action wins when several hooks fire. */
    operator fun plus(other: HookResult): HookResult {
        val mergedAction = listOf(action, other.action).maxByOrNull { it.rank } ?: HookAction.BLOCK
        return HookResult(
            action = mergedAction,
            message = listOf(message, other.message).filter(String::isNotBlank).joinToString("\n"),
            rewrittenArguments = rewrittenArguments ?: other.rewrittenArguments,
            auditEntries = auditEntries + other.auditEntries,
        )
    }

    companion object {
        val HookAction.rank: Int
            get() = when (this) {
                HookAction.ALLOW -> 0
                HookAction.ASK -> 1
                HookAction.REWRITE -> 2
                HookAction.CONTINUE -> 3
                HookAction.BLOCK -> 4
                HookAction.STOP -> 5
            }
    }
}

/** A single audited hook dispatch. */
data class HookAuditEntry(
    val timestampMillis: Long,
    val event: HookEvent,
    val ruleId: String?,
    val action: HookAction,
    val detail: String,
)

/**
 * Pure hook dispatch engine. It resolves the action for an event from a list of [HookRule]s plus a
 * permission check. Because hooks may *block* but never *grant* destructive work, a request that the
 * central policy would deny can never be turned into an ALLOW by any hook.
 */
object HookEngine {
    const val MAX_AUDIT_ENTRIES = 500

    data class Context(
        val permissionDecision: PermissionDecision? = null,
        val mutating: Boolean = false,
        val destructive: Boolean = false,
    )

    fun dispatch(
        event: HookEvent,
        toolName: String,
        rules: List<HookRule>,
        context: Context = Context(),
    ): HookResult {
        val matching = rules.filter { it.matches(event, toolName) }
        var result = HookResult()
        val audits = mutableListOf<HookAuditEntry>()
        for (rule in matching) {
            val partial = when (rule.action) {
                HookAction.ALLOW -> HookResult(HookAction.ALLOW, auditEntries = listOf(rule.message))
                HookAction.ASK -> HookResult(HookAction.ASK, rule.message)
                HookAction.REWRITE -> HookResult(HookAction.REWRITE, rule.message)
                HookAction.CONTINUE -> HookResult(HookAction.CONTINUE, rule.message)
                HookAction.BLOCK -> HookResult(HookAction.BLOCK, rule.message)
                HookAction.STOP -> HookResult(HookAction.STOP, rule.message)
            }
            audits += HookAuditEntry(System.currentTimeMillis(), event, rule.id, rule.action, rule.message)
            result += partial
        }
        // A hook can never downgrade a hard permission denial into an allow.
        if (context.permissionDecision is PermissionDecision.Denied && result.action == HookAction.ALLOW) {
            result = HookResult(HookAction.BLOCK, context.permissionDecision.reason.name)
        }
        // Destructive or secret-touching operations always require approval regardless of hooks.
        if ((context.destructive || context.mutating) && result.action == HookAction.ALLOW) {
            result = HookResult(HookAction.ASK, "This operation needs approval before it runs.")
        }
        return result.copy(auditEntries = result.auditEntries.ifEmpty { emptyList() })
    }

    /** Builds a permission request for a tool event so hooks share the central vocabulary. */
    fun permissionRequest(event: HookEvent, toolName: String, detail: String, mutating: Boolean, destructive: Boolean): PermissionRequest =
        PermissionRequest(
            subject = com.mobileharness.code.permission.PermissionSubject.TOOL,
            subjectId = toolName,
            operation = detail,
            mutating = mutating,
            destructive = destructive,
        )
}

/** Bounded in-memory audit log for hook dispatches (the Android layer persists it if desired). */
class HookAuditLog(private val maxEntries: Int = HookEngine.MAX_AUDIT_ENTRIES) {
    private val entries = ArrayDeque<HookAuditEntry>()

    fun record(entry: HookAuditEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    fun snapshot(): List<HookAuditEntry> = entries.toList()

    fun clear() = entries.clear()
}
