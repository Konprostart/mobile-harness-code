package com.mobileharness.code.automation

/**
 * Pure, fail-closed validation for scheduled tasks. A task that is empty, oversized, targets an
 * unknown agent, or recurs too aggressively (or at night under Eco Mode) is rejected, so the
 * scheduler can never wake the device in a way the user did not intend. Limits integrate with Eco
 * Mode exactly like the other policies.
 */
object SchedulePolicy {
    const val MAX_TASKS = 64
    const val MAX_PROMPT_CHARS = 8_000
    const val MAX_TITLE_CHARS = 120
    const val MAX_DESCRIPTION_CHARS = 500

    /** Minimum repeat interval in normal mode (30 minutes). */
    const val MIN_INTERVAL_MILLIS = 30 * 60 * 1_000L

    /** Minimum repeat interval under Eco Mode (6 hours). */
    const val MIN_INTERVAL_MILLIS_ECO = 6 * 60 * 60 * 1_000L

    /** Known agent kinds; a reminder/timer is a notification-only pseudo-agent. */
    private val KNOWN_AGENTS = setOf("claude", "deepseek", "antigravity", "sidekick", "reminder", "timer")

    /** Agents that do not require a project workspace (they operate globally). */
    private val PROJECTLESS_AGENTS = setOf("reminder", "timer", "sidekick")

    fun minIntervalMillis(ecoEnabled: Boolean): Long =
        if (ecoEnabled) MIN_INTERVAL_MILLIS_ECO else MIN_INTERVAL_MILLIS

    fun isValid(task: ScheduledTask, ecoEnabled: Boolean): Boolean {
        if (task.id.isBlank() || task.id.any { it.code < 0x20 }) return false
        if (task.agentKind.isBlank() || !KNOWN_AGENTS.contains(task.agentKind)) return false
        if (task.projectId.isBlank() && task.agentKind !in PROJECTLESS_AGENTS) return false
        if (task.prompt.isBlank() && task.agentKind !in setOf("reminder", "timer")) return false
        if (task.prompt.length > MAX_PROMPT_CHARS) return false
        if (task.title.length > MAX_TITLE_CHARS) return false
        if (task.description.length > MAX_DESCRIPTION_CHARS) return false
        if (task.nextRunMillis < 0L) return false
        if (task.recurrence == Recurrence.INTERVAL && task.intervalMillis < minIntervalMillis(ecoEnabled)) return false
        return true
    }

    /**
     * Whether a run is allowed right now. Eco Mode defers background work during quiet hours
     * (23:00–07:00 local) unless the task is a user-requested reminder/timer.
     */
    fun isRunAllowedNow(task: ScheduledTask, ecoEnabled: Boolean, hourOfDay: Int): Boolean {
        if (!ecoEnabled) return true
        if (task.agentKind in setOf("reminder", "timer")) return true
        return hourOfDay !in 23..23 && hourOfDay !in 0..6
    }
}
