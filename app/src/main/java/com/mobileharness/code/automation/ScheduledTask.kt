package com.mobileharness.code.automation

/**
 * Automation models: scheduled tasks (one-shot or repeating) that run a coding-agent prompt in the
 * background via WorkManager/AlarmManager, reminders and timers. The Android scheduling layer owns
 * the actual wake; this package is the pure, testable scheduling model + policy.
 */

/** How a task recurs. */
enum class Recurrence {
    /** Run once at [ScheduledTask.nextRunMillis]. */
    ONE_SHOT,

    /** Run every [ScheduledTask.intervalMillis] starting from nextRunMillis. */
    INTERVAL,
}

data class ScheduledTask(
    val id: String,
    val agentKind: String,
    val projectId: String,
    val prompt: String,
    val recurrence: Recurrence,
    val nextRunMillis: Long,
    val intervalMillis: Long = 0L,
    val enabled: Boolean = true,
    /** Optional reminder title for notification-only tasks (agentKind == "reminder"). */
    val title: String = "",
)
