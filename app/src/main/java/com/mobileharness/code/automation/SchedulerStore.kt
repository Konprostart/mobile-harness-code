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
                    .put("title", t.title),
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

    fun clear() {
        tasks.clear()
        commit()
    }

    private fun commit() = persist(ScheduledTaskCodec.encode(snapshot()))
}
