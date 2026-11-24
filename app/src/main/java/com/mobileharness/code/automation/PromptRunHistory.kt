package com.mobileharness.code.automation

import org.json.JSONArray
import org.json.JSONObject

/** Terminal state of a single auto-prompt execution. */
enum class PromptRunStatus { RUNNING, SUCCESS, FAILED }

/**
 * A single recorded execution of an auto prompt. Secret material (API keys) is never stored here:
 * only the agent, provider id and model name are kept for display. [resultText] and [errorText]
 * are size-capped by the writer so a single runaway run cannot grow the history unbounded.
 */
data class PromptRun(
    val id: String,
    val promptId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long = 0L,
    val agentKind: String,
    val providerId: String = "",
    val model: String = "",
    val status: PromptRunStatus,
    val resultText: String = "",
    val errorText: String = "",
) {
    val isRunning: Boolean get() = status == PromptRunStatus.RUNNING
}

/** Pure JSON codec for the run history; malformed entries are skipped on decode (fail-safe). */
object PromptRunCodec {
    const val MAX_RESULT_CHARS = 8_000
    const val MAX_ERROR_CHARS = 4_000

    fun encode(runs: List<PromptRun>): String {
        val array = JSONArray()
        runs.forEach { r ->
            array.put(
                JSONObject()
                    .put("id", r.id)
                    .put("promptId", r.promptId)
                    .put("startedAtMillis", r.startedAtMillis)
                    .put("finishedAtMillis", r.finishedAtMillis)
                    .put("agentKind", r.agentKind)
                    .put("providerId", r.providerId)
                    .put("model", r.model)
                    .put("status", r.status.name)
                    .put("resultText", r.resultText)
                    .put("errorText", r.errorText),
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<PromptRun> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val runs = mutableListOf<PromptRun>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val id = obj.optString("id")
            val promptId = obj.optString("promptId")
            if (id.isBlank() || promptId.isBlank()) continue
            val status = runCatching { PromptRunStatus.valueOf(obj.optString("status")) }.getOrNull() ?: continue
            runs += PromptRun(
                id = id,
                promptId = promptId,
                startedAtMillis = obj.optLong("startedAtMillis"),
                finishedAtMillis = obj.optLong("finishedAtMillis"),
                agentKind = obj.optString("agentKind"),
                providerId = obj.optString("providerId"),
                model = obj.optString("model"),
                status = status,
                resultText = obj.optString("resultText"),
                errorText = obj.optString("errorText"),
            )
        }
        return runs
    }

    /** Caps a result/error string to the store limit before it is persisted. */
    fun clamp(text: String, maxChars: Int = MAX_RESULT_CHARS): String =
        if (text.length <= maxChars) text else text.take(maxChars)
}

/**
 * Pure, in-memory history store for auto-prompt runs. Keeps at most [MAX_RUNS] entries (most recent
 * first) so storage stays bounded. [persist] is the same pattern as [SchedulerStore]: the Android
 * layer supplies the device-only writer.
 */
class AutoPromptHistoryStore(
    private val maxRuns: Int = 500,
    private val persist: (String) -> Unit = {},
) {
    private val runs = ArrayDeque<PromptRun>()

    fun load(json: String?) {
        runs.clear()
        PromptRunCodec.decode(json).forEach { runs.addLast(it) }
        trim()
    }

    fun snapshot(): List<PromptRun> = runs.toList()

    fun byPrompt(promptId: String): List<PromptRun> = runs.filter { it.promptId == promptId }

    fun latestFor(promptId: String): PromptRun? = runs.firstOrNull { it.promptId == promptId }

    fun record(run: PromptRun) {
        runs.addFirst(run)
        trim()
        commit()
    }

    /** Updates an existing run (e.g. to flip RUNNING → SUCCESS). No-op when the id is unknown. */
    fun update(id: String, transform: (PromptRun) -> PromptRun) {
        val index = runs.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = transform(runs[index])
        runs[index] = updated
        commit()
    }

    fun clearFor(promptId: String) {
        val remaining = runs.filter { it.promptId != promptId }
        runs.clear()
        remaining.forEach { runs.addLast(it) }
        commit()
    }

    fun clearAll() {
        runs.clear()
        commit()
    }

    private fun trim() {
        while (runs.size > maxRuns) runs.removeLast()
    }

    private fun commit() = persist(PromptRunCodec.encode(snapshot()))
}
