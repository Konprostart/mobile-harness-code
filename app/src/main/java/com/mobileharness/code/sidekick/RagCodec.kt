package com.mobileharness.code.sidekick

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON serialization for the assistant's document base, mirroring [com.mobileharness.code.mcp.McpServerCodec]
 * so the round-trip is unit-testable without an Android context. Malformed entries are dropped
 * (fail-closed) rather than throwing, and per-document text is truncated on load so a corrupt or
 * over-sized store cannot balloon memory.
 */
object RagCodec {
    fun toJson(documents: List<RagDocument>): String {
        val arr = JSONArray()
        documents.forEach { document ->
            arr.put(
                JSONObject()
                    .put("id", document.id)
                    .put("title", document.title)
                    .put("text", document.text)
                    .put("source", document.source),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<RagDocument> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (index in 0 until arr.length()) {
                    val obj = runCatching { arr.getJSONObject(index) }.getOrNull() ?: continue
                    val document = parseDocument(obj) ?: continue
                    add(document)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseDocument(obj: JSONObject): RagDocument? {
        val id = obj.optString("id").ifBlank { return null }
        val title = obj.optString("title")
        val text = obj.optString("text")
        if (text.isBlank()) return null
        return RagDocument(
            id = id,
            title = title,
            text = text.take(RagIndex.MAX_DOC_TEXT_CHARS),
            source = obj.optString("source"),
        )
    }
}
