package com.mobileharness.code.permission

import org.json.JSONArray
import org.json.JSONObject

/**
 * Append-only audit trail of every permission decision. Bounded, process-scoped, and redacted:
 * it never stores API keys, bearer tokens or other secrets — only the decision and the reasons.
 */
class PermissionAuditLog(
    private val maxEntries: Int = 500,
) {
    data class Entry(
        val timestampMillis: Long,
        val subject: PermissionSubject,
        val subjectId: String,
        val operation: String?,
        val outcome: Outcome,
        val detail: String?,
    )

    enum class Outcome { ALLOWED, NEEDS_APPROVAL, DENIED }

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(
        request: PermissionRequest,
        decision: PermissionDecision,
    ) {
        val (outcome, detail) = when (decision) {
            is PermissionDecision.Allowed -> Outcome.ALLOWED to decision.ruleId
            is PermissionDecision.NeedsApproval -> Outcome.NEEDS_APPROVAL to decision.reason
            is PermissionDecision.Denied -> Outcome.DENIED to decision.reason.name
        }
        entries.addLast(
            Entry(
                timestampMillis = System.currentTimeMillis(),
                subject = request.subject,
                subjectId = request.subjectId,
                operation = request.operation?.let(::redact),
                outcome = outcome,
                detail = detail,
            ),
        )
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun toJson(): String {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("ts", e.timestampMillis)
                    .put("subject", e.subject.name)
                    .put("subjectId", e.subjectId)
                    .put("operation", e.operation ?: JSONObject.NULL)
                    .put("outcome", e.outcome.name)
                    .put("detail", e.detail ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    /**
     * Removes anything that looks like a secret from free-text before it is stored or displayed.
     * Covers common key/token shapes; conservative and idempotent.
     */
    private fun redact(text: String): String {
        var out = text
        for (regex in REDACTION_REGEX) out = regex.replace(out, "[REDACTED]")
        return out
    }

    companion object {
        private val REDACTION_REGEX = listOf(
            Regex("""sk-[A-Za-z0-9_-]{8,}"""),
            Regex("""Bearer\s+[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
            Regex("""api[_-]?key["']?\s*[:=]\s*["']?[A-Za-z0-9._\-]{8,}""", RegexOption.IGNORE_CASE),
            Regex("""token["']?\s*[:=]\s*["']?[A-Za-z0-9._\-]{12,}""", RegexOption.IGNORE_CASE),
        )
    }
}
