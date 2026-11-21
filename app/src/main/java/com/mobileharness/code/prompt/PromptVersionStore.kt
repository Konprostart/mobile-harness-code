package com.mobileharness.code.prompt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned prompt store: keeps the last N assembled prompts (system + developer + task) so a good
 * prompt can be inspected or restored after a bad iteration. Pure JSON codec, bounded, no secrets
 * (task text may contain user content, so it is stored as-is but never the agent's credentials).
 */
class PromptVersionStore(
    private val maxVersions: Int = 20,
) {
    data class Version(
        val id: String,
        val timestampMillis: Long,
        val system: String,
        val developer: String,
        val task: String,
    )

    private val versions = ArrayDeque<Version>()

    @Synchronized
    fun save(prompt: AssembledPrompt) {
        versions.addLast(
            Version(
                id = prompt.versionId,
                timestampMillis = System.currentTimeMillis(),
                system = prompt.system,
                developer = prompt.developer,
                task = prompt.task,
            ),
        )
        while (versions.size > maxVersions) versions.removeFirst()
    }

    @Synchronized
    fun latest(): Version? = versions.lastOrNull()

    @Synchronized
    fun get(id: String): Version? = versions.firstOrNull { it.id == id }

    @Synchronized
    fun list(): List<Version> = versions.toList()

    @Synchronized
    fun toJson(): String {
        val array = JSONArray()
        versions.forEach { v ->
            array.put(
                JSONObject()
                    .put("id", v.id)
                    .put("ts", v.timestampMillis)
                    .put("system", v.system)
                    .put("developer", v.developer)
                    .put("task", v.task),
            )
        }
        return array.toString()
    }

    @Synchronized
    fun load(json: String?) {
        versions.clear()
        if (json.isNullOrBlank()) return
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            versions.addLast(
                Version(
                    id = id,
                    timestampMillis = obj.optLong("ts"),
                    system = obj.optString("system"),
                    developer = obj.optString("developer"),
                    task = obj.optString("task"),
                ),
            )
        }
    }

    @Synchronized
    fun clear() = versions.clear()
}
