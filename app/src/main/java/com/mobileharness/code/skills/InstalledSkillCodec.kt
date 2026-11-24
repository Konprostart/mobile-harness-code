package com.mobileharness.code.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON serialization for the installed SKILL.md skills, mirroring [SkillCodec] and
 * [com.mobileharness.code.mcp.McpServerCodec]. Malformed entries and invalid skills are dropped
 * (fail-closed) rather than throwing, and each field is bounded on load.
 */
object InstalledSkillCodec {
    fun toJson(skills: List<InstalledSkill>): String {
        val arr = JSONArray()
        skills.forEach { skill ->
            arr.put(
                JSONObject()
                    .put("id", skill.id)
                    .put("name", skill.name)
                    .put("description", skill.description)
                    .put("category", skill.category)
                    .put("body", skill.body)
                    .put("raw", skill.raw)
                    .put("fields", JSONObject(skill.fields))
                    .put("source", skill.source.name)
                    .put("sourceRef", skill.sourceRef)
                    .put("relativeDir", skill.relativeDir)
                    .put("files", JSONArray().apply { skill.files.forEach { put(it) } })
                    .put("tags", JSONArray().apply { skill.tags.forEach { put(it) } })
                    .put("version", skill.version)
                    .put("enabled", skill.enabled)
                    .put("installedAtMillis", skill.installedAtMillis)
                    .put("updatedAtMillis", skill.updatedAtMillis),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<InstalledSkill> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (index in 0 until arr.length()) {
                    val obj = runCatching { arr.getJSONObject(index) }.getOrNull() ?: continue
                    val skill = parse(obj) ?: continue
                    add(skill)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parse(obj: JSONObject): InstalledSkill? {
        val id = obj.optString("id").ifBlank { return null }
        val name = obj.optString("name").ifBlank { return null }
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val fields = mutableMapOf<String, String>()
        obj.optJSONObject("fields")?.let { fieldsObj ->
            fieldsObj.keys().forEach { key -> fields[key] = fieldsObj.optString(key) }
        }
        val files = stringList(obj.optJSONArray("files"))
        val tags = stringList(obj.optJSONArray("tags")).map { it.take(InstalledSkill.MAX_TAG_CHARS) }
            .take(InstalledSkill.MAX_TAGS)
        val source = runCatching { SkillSource.valueOf(obj.optString("source")) }
            .getOrDefault(SkillSource.USER)
        val skill = InstalledSkill(
            id = id.take(160),
            name = name.take(ParsedSkill.MAX_NAME),
            description = obj.optString("description").take(2000),
            category = obj.optString("category").ifBlank { "general" }.take(120),
            body = obj.optString("body"),
            raw = obj.optString("raw"),
            fields = fields,
            source = source,
            sourceRef = obj.optString("sourceRef").take(2048),
            relativeDir = obj.optString("relativeDir").take(120),
            files = files.take(InstalledSkill.MAX_FILES),
            tags = tags,
            version = obj.optString("version").ifBlank { "0.0.0" }.take(64),
            enabled = obj.optBoolean("enabled", true),
            installedAtMillis = obj.optLong("installedAtMillis"),
            updatedAtMillis = obj.optLong("updatedAtMillis"),
        )
        return if (skill.name.isBlank() || skill.relativeDir.isBlank()) null else skill
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i)
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
