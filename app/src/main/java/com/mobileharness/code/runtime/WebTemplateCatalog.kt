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
        return sorted(result)
    }

    /** Stable, category-ordered sort used for both bundled and merged catalogs. */
    fun sorted(templates: List<WebTemplate>): List<WebTemplate> =
        templates.sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))

    /**
     * Merges a bundled fallback with a remote catalog: remote entries win on id collision, while
     * bundled-only entries (offline bundles and informational entries) are retained.
     */
    fun merge(bundled: List<WebTemplate>, remote: List<WebTemplate>): List<WebTemplate> {
        val byId = linkedMapOf<String, WebTemplate>()
        bundled.forEach { byId[it.id] = it }
        remote.forEach { byId[it.id] = it }
        return sorted(byId.values.toList())
    }

    /** Number of entries that can actually be turned into an APK today (no build/backend). */
    fun readyCount(templates: List<WebTemplate>): Int = templates.count { it.isReady }

    /** Unique category ids present in the list, in declaration order. */
    fun categories(templates: List<WebTemplate>): List<TemplateCategory> =
        templates.map { it.category }.distinct().sortedBy { it.ordinal }

    /** Default catalog document embedded in the app as a fallback (empty until assets ship it). */
    val EMPTY = "[]"
}
