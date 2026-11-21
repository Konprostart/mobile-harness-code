package com.mobileharness.code.permission

/**
 * Pure, fail-closed decision engine for the unified permission system.
 *
 * Every agent/tool action is normalized to a [PermissionRequest] and resolved here against the
 * persistent [PermissionRule] set plus a small [Context]. The order of checks is deliberately
 * pessimistic: a hard block (emergency stop, secret access, destructive operation, out-of-scope
 * path, untrusted destination) is enforced *before* any allow rule is consulted, so no single
 * integration can bypass the central policy by merely carrying a broad grant.
 */
object PermissionPolicy {

    /** Runtime context that is not part of the persistent rule set. */
    data class Context(
        /** The global emergency stop is engaged. */
        val emergencyStopped: Boolean = false,
        /** Canonical workspace/project roots the agent is currently allowed to touch. */
        val workspaceRoots: Set<String> = emptySet(),
        /** Hosts (hostname, no scheme/port) considered trusted for outbound network. */
        val trustedHosts: Set<String> = emptySet(),
    )

    /**
     * Resolves [request] against [rules] and [context]. Never throws.
     */
    fun decide(
        request: PermissionRequest,
        rules: List<PermissionRule>,
        context: Context = Context(),
    ): PermissionDecision {
        if (request.subjectId.isBlank()) {
            return PermissionDecision.Denied(DenialReason.INVALID_REQUEST)
        }

        // 1. Emergency stop overrides everything.
        if (context.emergencyStopped) {
            return PermissionDecision.Denied(DenialReason.EMERGENCY_STOPPED)
        }

        // 2. Secret material is never auto-approved; it always surfaces to the user.
        if (request.touchesSecret) {
            return PermissionDecision.NeedsApproval("This action touches a secret or API key.")
        }

        // 3. Destructive operations are never auto-approved, even under ALLOW_ALL.
        if (request.destructive) {
            return PermissionDecision.NeedsApproval("This is a destructive operation and needs explicit approval.")
        }

        // 4. Out-of-scope paths are a hard block.
        if (request.targetPath != null && context.workspaceRoots.isNotEmpty() &&
            !isWithinAnyRoot(request.targetPath, context.workspaceRoots)
        ) {
            return PermissionDecision.Denied(DenialReason.OUTSIDE_WORKSPACE)
        }

        // 5. Untrusted network destinations are a hard block when a trust list is configured.
        if (request.destinationHost != null && context.trustedHosts.isNotEmpty() &&
            !isTrustedHost(request.destinationHost, context.trustedHosts)
        ) {
            return PermissionDecision.Denied(DenialReason.UNTRUSTED_DESTINATION)
        }

        // 6. Matching rules, from most to least specific.
        val matching = rules.filter { matches(it, request) }

        // 7. An explicit DENY always wins, regardless of specificity.
        if (matching.any { it.level == PermissionLevel.DENY }) {
            return PermissionDecision.Denied(DenialReason.EXPLICIT_DENY)
        }

        // 8. Pick the most specific allow rule.
        val rule = matching.maxByOrNull { specificity(it, request) }
            ?: return PermissionDecision.NeedsApproval("No permission rule matches \"${request.subjectId}\".")

        return when (rule.level) {
            PermissionLevel.DENY ->
                PermissionDecision.Denied(DenialReason.EXPLICIT_DENY)
            PermissionLevel.ASK_EVERY_TIME ->
                PermissionDecision.NeedsApproval("This action is set to ask every time.")
            PermissionLevel.ALLOW_ONCE,
            PermissionLevel.ALLOW_SESSION,
            PermissionLevel.ALWAYS_ALLOW,
            PermissionLevel.ALLOW_ALL,
            -> PermissionDecision.Allowed(rule.id)
        }
    }

    private fun matches(rule: PermissionRule, request: PermissionRequest): Boolean {
        if (rule.subject != request.subject) return false
        val ruleId = rule.subjectId
        if (ruleId != "*" && ruleId != request.subjectId) return false
        if (rule.projectId != null && rule.projectId != request.projectId) return false
        if (rule.workspacePath != null && rule.workspacePath != request.workspacePath) return false
        return true
    }

    /** Higher = more specific; a narrower scope beats a global rule. */
    private fun specificity(rule: PermissionRule, request: PermissionRequest): Int {
        var score = 0
        if (rule.projectId != null && rule.projectId == request.projectId) score += 2
        if (rule.workspacePath != null && rule.workspacePath == request.workspacePath) score += 2
        if (rule.subjectId != "*") score += 1
        return score
    }

    private fun isWithinAnyRoot(target: String, roots: Set<String>): Boolean {
        val normalized = normalizePath(target) ?: return false
        return roots.any { root ->
            val normalizedRoot = normalizePath(root) ?: return@any false
            normalized == normalizedRoot || normalized.startsWith(normalizedRoot + "/")
        }
    }

    private fun isTrustedHost(host: String, trusted: Set<String>): Boolean {
        val h = host.trim().lowercase().removeSuffix(".")
        return trusted.any { h == it.trim().lowercase() || h.endsWith("." + it.trim().lowercase()) }
    }

    /** Best-effort lexical normalization (no filesystem access; the real confinement is elsewhere). */
    private fun normalizePath(path: String): String? {
        val trimmed = path.trim().ifBlank { return null }
        val parts = mutableListOf<String>()
        for (segment in trimmed.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }
}
