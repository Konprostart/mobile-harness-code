package com.mobileharness.code.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads and validates the bundled Template Library catalog. The catalog is a single JSON document
 * shipped as an app asset (`templates/catalog.json`); parsing is fail-closed so a malformed or
 * tampered entry is dropped rather than presented as a usable template.
 */
object WebTemplateCatalog {
    /** Validates and sorts catalog JSON into a stable, category-ordered list. */
    fun fromJson(json: String): List<WebTemplate> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<WebTemplate>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val template = runCatching { WebTemplate.fromJson(obj) }.getOrNull() ?: continue
            if (WebTemplatePolicy.isValid(template)) result += template
        }
        return result.sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))
    }

    /** Number of entries that can actually be turned into an APK today (no build/backend). */
    fun readyCount(templates: List<WebTemplate>): Int = templates.count { it.isReady }

    /** Unique category ids present in the list, in declaration order. */
    fun categories(templates: List<WebTemplate>): List<TemplateCategory> =
        templates.map { it.category }.distinct().sortedBy { it.ordinal }

    /** Default catalog document embedded in the app as a fallback (empty until assets ship it). */
    val EMPTY = "[]"
}
