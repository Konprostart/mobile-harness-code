package com.mobileharness.code.runtime

/**
 * Pure, testable policy for the optional "Eco Mode" (Token Saver) toggle.
 *
 * Eco Mode is strictly opt-in and never disables a feature the user explicitly started.
 * It only shrinks costs that scale with verbosity or polling:
 *
 *  - Token Saver: the Sidekick assistant asks for a smaller completion budget and gathers
 *    less background-research context, so long overnight runs spend fewer tokens.
 *  - Night throttling: background polling relaxes during quiet hours (00:00–06:00) when no
 *    user task is running, trading latency for battery and CPU.
 *
 * Everything here is deterministic and side-effect free so the policy is unit-testable and
 * the UI can describe exactly what Eco Mode changes.
 */
object EcoMode {
    /** Completion budget used by the Sidekick assistant in normal mode. */
    const val NORMAL_MAX_TOKENS = 2048

    /** Completion budget used by the Sidekick assistant when Eco Mode is on. */
    const val ECO_MAX_TOKENS = 768

    /** Quiet hours: [NIGHT_START_HOUR, NIGHT_END_HOUR), local time. */
    const val NIGHT_START_HOUR = 0
    const val NIGHT_END_HOUR = 6

    /** Background research context limits in normal mode (mirrors SidekickResearch defaults). */
    const val NORMAL_RESEARCH_FILES = 200
    const val NORMAL_RESEARCH_BYTES = 1_000_000L

    /** Reduced background research context limits in Eco Mode. */
    const val ECO_RESEARCH_FILES = 60
    const val ECO_RESEARCH_BYTES = 250_000L

    /** Poll intervals for background watchers. */
    const val NORMAL_POLL_INTERVAL_MS = 60_000L
    const val ECO_POLL_INTERVAL_MS = 5 * 60_000L
    const val QUIET_POLL_INTERVAL_MS = 15 * 60_000L

    /** The maximum completion tokens the Sidekick assistant should request. */
    fun recommendedMaxTokens(ecoEnabled: Boolean): Int =
        if (ecoEnabled) ECO_MAX_TOKENS else NORMAL_MAX_TOKENS

    /** Whether [hourOfDay] (0–23, local time) falls inside the night quiet window. */
    fun isQuietHour(hourOfDay: Int): Boolean =
        hourOfDay in NIGHT_START_HOUR until NIGHT_END_HOUR

    /** Maximum number of files background research may read in the current mode. */
    fun researchFileLimit(ecoEnabled: Boolean): Int =
        if (ecoEnabled) ECO_RESEARCH_FILES else NORMAL_RESEARCH_FILES

    /** Maximum total bytes background research may gather in the current mode. */
    fun researchByteLimit(ecoEnabled: Boolean): Long =
        if (ecoEnabled) ECO_RESEARCH_BYTES else NORMAL_RESEARCH_BYTES

    /**
     * Interval a background watcher should sleep between idle polls. An active user task
     * always keeps the normal cadence; Eco Mode relaxes it further during quiet hours.
     */
    fun backgroundPollIntervalMillis(ecoEnabled: Boolean, hourOfDay: Int, hasActiveTask: Boolean): Long {
        if (!ecoEnabled || hasActiveTask) return NORMAL_POLL_INTERVAL_MS
        return if (isQuietHour(hourOfDay)) QUIET_POLL_INTERVAL_MS else ECO_POLL_INTERVAL_MS
    }
}
