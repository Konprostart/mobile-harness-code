package com.mobileharness.code.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON store for scheduled tasks (and its codec). The Android layer persists the JSON in
 * device-only storage and drives WorkManager/AlarmManager from it; this class is unit-testable and
 * fail-safe on decode (malformed entries are skipped).
 */
object ScheduledTaskCodec {

    fun encode(tasks: List<ScheduledTask>): String {
        val array = JSONArray()
        tasks.forEach { t ->
            array.put(
                JSONObject()
                    .put("id", t.id)
                    .put("agentKind", t.agentKind)
                    .put("projectId", t.projectId)
                    .put("prompt", t.prompt)
                    .put("recurrence", t.recurrence.name)
                    .put("nextRunMillis", t.nextRunMillis)
                    .put("intervalMillis", t.intervalMillis)
                    .put("enabled", t.enabled)
                    .put("title", t.title)
                    .put("description", t.description)
                    .put("providerId", t.providerId)
                    .put("model", t.model)
                    .put("runMode", t.runMode.name)
                    .put("lastRunMillis", t.lastRunMillis)
                    .put("lastRunStatus", t.lastRunStatus),
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<ScheduledTask> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val tasks = mutableListOf<ScheduledTask>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val recurrence = runCatching { Recurrence.valueOf(obj.optString("recurrence")) }.getOrNull() ?: continue
            val runMode = runCatching { RunMode.valueOf(obj.optString("runMode")) }.getOrNull() ?: RunMode.SCHEDULED
            tasks += ScheduledTask(
                id = id,
                agentKind = obj.optString("agentKind"),
                projectId = obj.optString("projectId"),
                prompt = obj.optString("prompt"),
                recurrence = recurrence,
                nextRunMillis = obj.optLong("nextRunMillis"),
                intervalMillis = obj.optLong("intervalMillis"),
                enabled = obj.optBoolean("enabled", true),
                title = obj.optString("title"),
                description = obj.optString("description"),
                providerId = obj.optString("providerId"),
                model = obj.optString("model"),
                runMode = runMode,
                lastRunMillis = obj.optLong("lastRunMillis"),
                lastRunStatus = obj.optString("lastRunStatus"),
            )
        }
        return tasks
    }
}

class SchedulerStore(
    private val persist: (String) -> Unit = {},
) {
    private val tasks = LinkedHashMap<String, ScheduledTask>()

    fun load(json: String?) {
        tasks.clear()
        ScheduledTaskCodec.decode(json).forEach { tasks[it.id] = it }
    }

    fun snapshot(): List<ScheduledTask> = tasks.values.toList()

    fun get(id: String): ScheduledTask? = tasks[id]

    fun upsert(task: ScheduledTask): Boolean {
        if (!SchedulePolicy.isValid(task, ecoEnabled = false)) return false
        tasks[task.id] = task
        commit()
        return true
    }

    fun remove(id: String): Boolean {
        val removed = tasks.remove(id) != null
        if (removed) commit()
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val existing = tasks[id] ?: return false
        tasks[id] = existing.copy(enabled = enabled)
        commit()
        return true
    }

    /** Reorders tasks so their ids appear in the given order (unknown ids are appended, untouched). */
    fun reorder(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val byId = tasks
        val reordered = LinkedHashMap<String, ScheduledTask>()
        orderedIds.forEach { id -> byId[id]?.let { reordered[id] = it } }
        byId.forEach { (id, task) -> if (id !in reordered) reordered[id] = task }
        tasks.clear()
        tasks.putAll(reordered)
        commit()
    }

    /** Duplicates an existing task under a fresh id, appending " (copy)" to the title. */
    fun duplicate(id: String): ScheduledTask? {
        val source = tasks[id] ?: return null
        val copy = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            title = source.title.ifBlank { source.description.ifBlank { "Prompt" } } + " (copy)",
            lastRunMillis = 0L,
            lastRunStatus = "",
        )
        if (!SchedulePolicy.isValid(copy, ecoEnabled = false)) return null
        tasks[copy.id] = copy
        commit()
        return copy
    }

    /** Records the outcome of a run on the owning task (used for the list's "last run" summary). */
    fun touchLastRun(id: String, startedAtMillis: Long, status: PromptRunStatus): Boolean {
        val existing = tasks[id] ?: return false
        tasks[id] = existing.copy(lastRunMillis = startedAtMillis, lastRunStatus = status.name)
        commit()
        return true
    }

    fun clear() {
        tasks.clear()
        commit()
    }

    private fun commit() = persist(ScheduledTaskCodec.encode(snapshot()))
}
