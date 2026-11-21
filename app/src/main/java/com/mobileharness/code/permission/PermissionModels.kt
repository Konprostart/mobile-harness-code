package com.mobileharness.code.permission

/**
 * Data model for the unified permission system.
 *
 * One permission vocabulary is shared by every agent, tool and integration (Claude Code, DeepSeek
 * Harness, Antigravity, MCP tools, built-in tools, terminal/Bash, workspace files, plugins, web
 * research, wireless ADB / Shizuku / root). Keeping the model pure JVM makes every decision unit
 * testable without an Android device, mirroring [com.mobileharness.code.device.PrivilegePolicy].
 */

/** How much autonomy a rule grants for its subject. */
enum class PermissionLevel {
    /** Always surface the request to the user before acting. */
    ASK_EVERY_TIME,

    /** Approve this single action; the grant is consumed after one use. */
    ALLOW_ONCE,

    /** Approve for the rest of the current session (until the process/session ends). */
    ALLOW_SESSION,

    /** Persist approval for this subject + scope across sessions. */
    ALWAYS_ALLOW,

    /** Full autonomy for non-destructive, in-scope actions (destructive ops still ask). */
    ALLOW_ALL,

    /** Explicitly reject the request. */
    DENY,
}

/** What a rule governs. */
enum class PermissionSubject {
    /** A coding agent engine (Claude Code, DeepSeek, Antigravity). */
    AGENT,

    /** A specific tool (Bash, Edit, Write, a built-in tool, an MCP tool name). */
    TOOL,

    /** A class of operation (e.g. "file.delete", "git.reset", "shell.exec"). */
    OPERATION,

    /** A file or directory path. */
    PATH,

    /** A whole project. */
    PROJECT,

    /** A whole workspace. */
    WORKSPACE,

    /** A plugin id. */
    PLUGIN,

    /** An MCP server id. */
    MCP_SERVER,

    /** A privileged Android operation (wireless ADB / Shizuku / root). */
    ANDROID,
}

/** Why a request was denied. Stable, machine-readable, and safe to surface to the user. */
enum class DenialReason {
    /** A matching DENY rule (or a rule that otherwise resolves to deny) rejected it. */
    EXPLICIT_DENY,

    /** No matching rule existed and the default policy is fail-closed. */
    FAIL_CLOSED_NO_RULE,

    /** Destructive operations may never be auto-approved. */
    DESTRUCTIVE_REQUIRES_ASK,

    /** The target path resolves outside the approved workspace/project. */
    OUTSIDE_WORKSPACE,

    /** The operation touches secret material (API keys / credentials). */
    SECRET_ACCESS,

    /** The destination host is not on the trusted list. */
    UNTRUSTED_DESTINATION,

    /** The global emergency stop is engaged. */
    EMERGENCY_STOPPED,

    /** The request itself was malformed (blank subject, oversized payload). */
    INVALID_REQUEST,
}

/** A single permission decision request, normalized from any integration's native format. */
data class PermissionRequest(
    val subject: PermissionSubject,
    val subjectId: String,
    /** Human-readable description of the concrete action, for the audit log and approval UI. */
    val operation: String? = null,
    /** Current project id, when one is in scope. */
    val projectId: String? = null,
    /** Current workspace path, when one is in scope. */
    val workspacePath: String? = null,
    /** Whether the action mutates state. */
    val mutating: Boolean = false,
    /** Whether the action is destructive (irreversible) — always needs approval. */
    val destructive: Boolean = false,
    /** Whether the action reads or writes secret material (API keys / tokens). */
    val touchesSecret: Boolean = false,
    /** A file/dir path the action targets, when relevant. */
    val targetPath: String? = null,
    /** A network destination the action would reach, when relevant. */
    val destinationHost: String? = null,
)

/** Outcome of [PermissionPolicy.decide]. */
sealed interface PermissionDecision {
    /** Approved; [ruleId] is the matching rule when a persistent rule granted it. */
    data class Allowed(val ruleId: String? = null) : PermissionDecision

    /** Not auto-approved; must be surfaced to the user (fail-closed in unattended contexts). */
    data class NeedsApproval(val reason: String) : PermissionDecision

    /** Rejected. */
    data class Denied(val reason: DenialReason) : PermissionDecision
}

/**
 * A persistent grant/deny for one subject, optionally narrowed to a project or workspace.
 * `null` [projectId]/[workspacePath] means the rule is global.
 */
data class PermissionRule(
    val id: String,
    val subject: PermissionSubject,
    val subjectId: String,
    val level: PermissionLevel,
    val projectId: String? = null,
    val workspacePath: String? = null,
)
