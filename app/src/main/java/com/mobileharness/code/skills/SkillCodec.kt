package com.mobileharness.code.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON serialization for the skill list, mirroring [com.mobileharness.code.mcp.McpServerCodec].
 * Malformed entries and invalid skills are dropped (fail-closed) rather than throwing, and each
 * field is bounded on load.
 */
object SkillCodec {
    fun toJson(skills: List<Skill>): String {
        val arr = JSONArray()
        skills.forEach { skill ->
            arr.put(
                JSONObject()
                    .put("id", skill.id)
                    .put("name", skill.name)
                    .put("description", skill.description)
                    .put("instructions", skill.instructions)
                    .put("enabled", skill.enabled),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<Skill> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (index in 0 until arr.length()) {
                    val obj = runCatching { arr.getJSONObject(index) }.getOrNull() ?: continue
                    val skill = Skill(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        description = obj.optString("description"),
                        instructions = obj.optString("instructions"),
                        enabled = obj.optBoolean("enabled", false),
                    )
                    if (SkillPolicy.isValid(skill)) add(SkillPolicy.sanitize(skill))
                }
            }
        }.getOrDefault(emptyList())
    }
}
