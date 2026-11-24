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

/**
 * How an auto prompt is started. [MANUAL] prompts run only when the user presses RUN and are never
 * fired by the scheduler; [SCHEDULED] prompts fire automatically according to [ScheduledTask.recurrence]
 * when both the master switch and the per-prompt switch are on.
 */
enum class RunMode {
    MANUAL,
    SCHEDULED,
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
    /** Human-readable description shown in the Auto Prompt list (distinct from the instruction text). */
    val description: String = "",
    /** Library provider id (e.g. "openai", "groq") used to resolve the API endpoint; blank = default. */
    val providerId: String = "",
    /** Model name; blank falls back to the selected provider's default model. */
    val model: String = "",
    /** Manual vs scheduled start. */
    val runMode: RunMode = RunMode.SCHEDULED,
    /** Epoch millis of the most recent run start (0 = never ran). */
    val lastRunMillis: Long = 0L,
    /** Last run status, as [PromptRunStatus.name]; blank = never ran. */
    val lastRunStatus: String = "",
)
