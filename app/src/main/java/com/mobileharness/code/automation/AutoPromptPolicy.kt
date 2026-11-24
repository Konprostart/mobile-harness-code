package com.mobileharness.code.automation

/**
 * Pure decision logic for the Auto Prompt system, kept separate from Android so it is fully
 * unit-testable. It answers two questions:
 *
 *  1. Should this task run automatically *now* (the scheduler's gate), given the master switch,
 *     the per-prompt switch, its run mode, and the current local hour (Eco Mode quiet hours)?
 *  2. May a manual RUN proceed (an explicit user action that must never be silently swallowed)?
 *
 * Manual runs are always allowed regardless of the master switch or per-prompt `enabled` flag —
 * pressing RUN is explicit consent — but they are still refused when a run for the same prompt is
 * already in flight (double-run guard).
 */
object AutoPromptPolicy {

    /**
     * Scheduler gate: true only when the master switch is on, this prompt is enabled, it is a
     * SCHEDULED prompt, and Eco Mode does not defer it during quiet hours.
     */
    fun shouldSchedule(
        task: ScheduledTask,
        masterEnabled: Boolean,
        ecoEnabled: Boolean,
        hourOfDay: Int,
    ): Boolean {
        if (!masterEnabled) return false
        if (!task.enabled) return false
        if (task.runMode != RunMode.SCHEDULED) return false
        return SchedulePolicy.isRunAllowedNow(task, ecoEnabled, hourOfDay)
    }

    /** Manual RUN is explicit consent: allowed for any valid prompt unless it is already running. */
    fun mayRunManually(task: ScheduledTask, alreadyRunning: Boolean): Boolean {
        if (alreadyRunning) return false
        return task.prompt.isNotBlank()
    }

    /**
     * Advances a recurring task to its next due time after a run, guarding against a run-away
     * loop: the returned time is always strictly in the future relative to [nowMillis].
     */
    fun nextRunAfter(task: ScheduledTask, nowMillis: Long): Long {
        if (task.recurrence != Recurrence.INTERVAL) return 0L
        val interval = task.intervalMillis.coerceAtLeast(1L)
        var next = nowMillis + interval
        while (next <= nowMillis) next += interval
        return next
    }
}
