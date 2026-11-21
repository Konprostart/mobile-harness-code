package com.mobileharness.code.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, bounded, redacted provider request log.
 *
 * Unlike the process-scoped [RouteLog] ring buffer, this log survives restarts and is meant to back
 * the "Provider" diagnostics screen. Secrets are stripped *before* an entry is stored, so no API
 * key or bearer token ever reaches disk. Rotation is handled by the caller via [toJson]/[load]; the
 * class itself only enforces the entry cap and redaction.
 */
class ProviderRequestLog(
    private val maxEntries: Int = 500,
    private val persist: (String) -> Unit = {},
) {
    enum class Kind { SUCCESS, AUTH_FAILURE, RATE_LIMITED, SERVER_ERROR, TIMEOUT, NETWORK, BAD_REQUEST, OTHER }

    data class Entry(
        val timestampMillis: Long,
        val provider: String,
        val model: String,
        val kind: Kind,
        val statusCode: Int?,
        val latencyMillis: Long?,
        val detail: String?,
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(
        provider: String,
        model: String,
        kind: Kind,
        statusCode: Int? = null,
        latencyMillis: Long? = null,
        detail: String? = null,
    ) {
        entries.addLast(
            Entry(
                timestampMillis = System.currentTimeMillis(),
                provider = provider,
                model = model,
                kind = kind,
                statusCode = statusCode,
                latencyMillis = latencyMillis,
                detail = detail?.let(::redact),
            ),
        )
        while (entries.size > maxEntries) entries.removeFirst()
        commit()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        commit()
    }

    @Synchronized
    fun load(json: String?) {
        entries.clear()
        if (json.isNullOrBlank()) return
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            entries.addLast(
                Entry(
                    timestampMillis = obj.optLong("ts"),
                    provider = obj.optString("provider"),
                    model = obj.optString("model"),
                    kind = runCatching { Kind.valueOf(obj.optString("kind")) }.getOrDefault(Kind.OTHER),
                    statusCode = if (obj.has("status") && obj.opt("status") != JSONObject.NULL) obj.optInt("status") else null,
                    latencyMillis = if (obj.has("latency") && obj.opt("latency") != JSONObject.NULL) obj.optLong("latency") else null,
                    detail = obj.optString("detail").takeIf { it.isNotBlank() },
                ),
            )
        }
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun toJson(): String {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("ts", e.timestampMillis)
                    .put("provider", e.provider)
                    .put("model", e.model)
                    .put("kind", e.kind.name)
                    .put("status", e.statusCode ?: JSONObject.NULL)
                    .put("latency", e.latencyMillis ?: JSONObject.NULL)
                    .put("detail", e.detail ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    private fun commit() = persist(toJson())

    companion object {
        private val REDACTION_REGEX = listOf(
            Regex("""sk-[A-Za-z0-9_-]{8,}"""),
            Regex("""Bearer\s+[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
            Regex("""api[_-]?key["']?\s*[:=]\s*["']?[A-Za-z0-9._\-]{8,}""", RegexOption.IGNORE_CASE),
            Regex("""token["']?\s*[:=]\s*["']?[A-Za-z0-9._\-]{12,}""", RegexOption.IGNORE_CASE),
            Regex("""key=[A-Za-z0-9._\-]{8,}""", RegexOption.IGNORE_CASE),
        )

        /** Removes common secret shapes from free text. Idempotent and safe to call on any string. */
        fun redact(text: String): String {
            var out = text
            for (regex in REDACTION_REGEX) out = regex.replace(out, "[REDACTED]")
            return out
        }
    }
}
