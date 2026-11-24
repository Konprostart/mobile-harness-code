package com.mobileharness.code.token

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One recorded provider request for the Token & Cost Manager.
 *
 * Secrets are never stored: only provider/model labels, agent/session/project labels and numeric
 * usage. [reliability] declares how trustworthy the token figures are (actual / estimated / not
 * tracked). [tokensEstimated] is retained for backward compatibility with previously persisted
 * entries and is equivalent to [Reliability.ESTIMATED].
 */
data class TokenUsageEntry(
    val timestampMillis: Long,
    val providerId: String,
    val model: String,
    val agent: String,
    val usage: TokenUsage,
    val tokensEstimated: Boolean = false,
    val requestCount: Int = 1,
    val latencyMillis: Long? = null,
    val projectId: String = "",
    val sessionId: String = "",
    val task: String = "",
    val currency: String = "USD",
    val source: UsageSource = UsageSource.API,
    /** Monetary cost reported by the provider itself (e.g. Claude Code `total_cost_usd`), when known. */
    val reportedCostUsd: Double? = null,
    val reliability: Reliability = if (tokensEstimated) Reliability.ESTIMATED else Reliability.ACTUAL,
)

/** Pure JSON codec for the usage store; independent of Android so it is unit-testable. */
object TokenUsageCodec {
    fun toJson(entries: List<TokenUsageEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("ts", entry.timestampMillis)
                    .put("providerId", entry.providerId)
                    .put("model", entry.model)
                    .put("agent", entry.agent)
                    .put("input", entry.usage.inputTokens)
                    .put("output", entry.usage.outputTokens)
                    .put("cachedRead", entry.usage.cachedReadTokens)
                    .put("cacheWrite", entry.usage.cacheWriteTokens)
                    .put("estimated", entry.tokensEstimated)
                    .put("requests", entry.requestCount)
                    .put("latency", entry.latencyMillis ?: JSONObject.NULL)
                    .put("projectId", entry.projectId)
                    .put("sessionId", entry.sessionId)
                    .put("task", entry.task)
                    .put("currency", entry.currency)
                    .put("source", entry.source.name)
                    .put("reportedCostUsd", entry.reportedCostUsd ?: JSONObject.NULL)
                    .put("rel", entry.reliability.name),
            )
        }
        return array.toString()
    }

    fun fromJson(raw: String): List<TokenUsageEntry> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val usage = TokenUsage(
                    inputTokens = obj.optLong("input", 0L),
                    outputTokens = obj.optLong("output", 0L),
                    cachedReadTokens = obj.optLong("cachedRead", 0L),
                    cacheWriteTokens = obj.optLong("cacheWrite", 0L),
                )
                val estimated = obj.optBoolean("estimated", false)
                add(
                    TokenUsageEntry(
                        timestampMillis = obj.optLong("ts", 0L),
                        providerId = obj.optString("providerId"),
                        model = obj.optString("model"),
                        agent = obj.optString("agent"),
                        usage = usage,
                        tokensEstimated = estimated,
                        requestCount = obj.optInt("requests", 1).coerceAtLeast(1),
                        latencyMillis = if (obj.has("latency") && obj.opt("latency") != JSONObject.NULL) obj.optLong("latency") else null,
                        projectId = obj.optString("projectId"),
                        sessionId = obj.optString("sessionId"),
                        task = obj.optString("task"),
                        currency = obj.optString("currency").ifBlank { "USD" },
                        source = UsageSource.entries.firstOrNull { it.name == obj.optString("source") } ?: UsageSource.API,
                        reportedCostUsd = if (obj.has("reportedCostUsd") && obj.opt("reportedCostUsd") != JSONObject.NULL) obj.optDouble("reportedCostUsd") else null,
                        reliability = Reliability.entries.firstOrNull { it.name == obj.optString("rel") }
                            ?: if (estimated) Reliability.ESTIMATED else Reliability.ACTUAL,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * File-backed, bounded store of token-usage entries. Appends in timestamp order and trims to
 * [maxEntries] (oldest first out) so a long-running install never grows without bound. Writes are
 * atomic (temp file + rename) exactly like the other file stores in the app.
 */
class TokenUsageStore(private val dir: File, private val maxEntries: Int = 2000) {
    private val file: File get() = File(dir, "token_usage.json")

    @Synchronized
    fun load(): List<TokenUsageEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { TokenUsageCodec.fromJson(file.readText()) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(entry: TokenUsageEntry) {
        val entries = (load() + entry).sortedBy { it.timestampMillis }
        val trimmed = if (entries.size > maxEntries) entries.takeLast(maxEntries) else entries
        write(trimmed)
    }

    @Synchronized
    fun recordAll(newEntries: List<TokenUsageEntry>) {
        if (newEntries.isEmpty()) return
        val entries = (load() + newEntries).sortedBy { it.timestampMillis }
        val trimmed = if (entries.size > maxEntries) entries.takeLast(maxEntries) else entries
        write(trimmed)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(entries: List<TokenUsageEntry>) {
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, ".token_usage.json.tmp")
            temporary.writeText(TokenUsageCodec.toJson(entries))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }
}
