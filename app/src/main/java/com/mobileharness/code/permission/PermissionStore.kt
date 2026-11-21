package com.mobileharness.code.permission

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON codec for [PermissionRule]. Format is a flat JSON array so it can be stored in
 * SharedPreferences (device-only) or a file. Malformed/unknown entries are skipped on decode rather
 * than throwing, matching the fail-safe decode behaviour used by the MCP and Skill codecs.
 */
object PermissionRuleCodec {

    fun encode(rules: List<PermissionRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("subject", rule.subject.name)
                    .put("subjectId", rule.subjectId)
                    .put("level", rule.level.name)
                    .put("projectId", rule.projectId ?: JSONObject.NULL)
                    .put("workspacePath", rule.workspacePath ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<PermissionRule> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val rules = mutableListOf<PermissionRule>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val subject = runCatching { PermissionSubject.valueOf(obj.optString("subject")) }.getOrNull() ?: continue
            val subjectId = obj.optString("subjectId")
            if (subjectId.isBlank()) continue
            val level = runCatching { PermissionLevel.valueOf(obj.optString("level")) }.getOrNull() ?: continue
            val projectId = obj.optString("projectId").takeIf { it.isNotBlank() && obj.opt("projectId") != JSONObject.NULL }
            val workspacePath = obj.optString("workspacePath").takeIf { it.isNotBlank() && obj.opt("workspacePath") != JSONObject.NULL }
            rules += PermissionRule(
                id = id,
                subject = subject,
                subjectId = subjectId,
                level = level,
                projectId = projectId,
                workspacePath = workspacePath,
            )
        }
        return rules
    }
}

/**
 * In-memory rule registry with an optional persistence sink. The Android layer owns the actual
 * SharedPreferences/File persistence and passes it in as a lambda, keeping this class pure and
 * unit-testable.
 */
class PermissionStore(
    private val persist: (String) -> Unit = {},
) {
    private val rules = LinkedHashMap<String, PermissionRule>()

    fun load(json: String?) {
        rules.clear()
        PermissionRuleCodec.decode(json).forEach { rules[it.id] = it }
    }

    fun snapshot(): List<PermissionRule> = rules.values.toList()

    /** Returns the rules relevant to a request (subject match), most specific first. */
    fun rulesFor(request: PermissionRequest): List<PermissionRule> = snapshot()

    fun upsert(rule: PermissionRule) {
        rules[rule.id] = rule
        commit()
    }

    fun remove(id: String): Boolean {
        val removed = rules.remove(id) != null
        if (removed) commit()
        return removed
    }

    /** Consumes all one-shot grants (called after an ALLOW_ONCE rule is used). */
    fun consumeAllowOnce(id: String): Boolean {
        val rule = rules[id] ?: return false
        if (rule.level != PermissionLevel.ALLOW_ONCE) return false
        rules.remove(id)
        commit()
        return true
    }

    /** Clears session-scoped grants (called when a session ends). */
    fun clearSessionScoped() {
        val toRemove = rules.values.filter { it.level == PermissionLevel.ALLOW_SESSION }.map { it.id }
        if (toRemove.isEmpty()) return
        toRemove.forEach { rules.remove(it) }
        commit()
    }

    fun clear() {
        rules.clear()
        commit()
    }

    private fun commit() = persist(PermissionRuleCodec.encode(snapshot()))
}
