package com.mobileharness.code.automation

/**
 * Parses a small, deterministic schedule expression into a [ScheduledTask] recurrence. Supported:
 *
 *  - `"once at <epoch-millis>"` → ONE_SHOT
 *  - `"every <N>m|h|d"` → INTERVAL with the corresponding millis
 *
 * The parser is intentionally small and predictable (no full cron grammar); it exists so an agent
 * or the UI can express a schedule without a natural-language model call, and it always fails
 * closed on unrecognized input.
 */
object ScheduleParser {

    data class Parsed(
        val recurrence: Recurrence,
        val intervalMillis: Long,
        val nextRunMillis: Long,
    )

    fun parse(expression: String, nowMillis: Long, ecoEnabled: Boolean): Parsed? {
        val input = expression.trim()
        if (input.isEmpty()) return null

        if (input.startsWith("once at ")) {
            val epoch = input.removePrefix("once at ").trim().toLongOrNull() ?: return null
            if (epoch < 0L) return null
            return Parsed(Recurrence.ONE_SHOT, 0L, epoch)
        }

        if (input.startsWith("every ")) {
            val spec = input.removePrefix("every ").trim()
            val interval = parseInterval(spec) ?: return null
            if (interval < SchedulePolicy.minIntervalMillis(ecoEnabled)) return null
            return Parsed(Recurrence.INTERVAL, interval, nowMillis + interval)
        }

        return null
    }

    /** Parses `"30m"`, `"2h"`, `"1d"` into millis. */
    fun parseInterval(spec: String): Long? {
        val match = Regex("^(\\d+)\\s*(m|h|d)$").matchEntire(spec.trim()) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2]
        val millis = when (unit) {
            "m" -> value * 60_000L
            "h" -> value * 60 * 60_000L
            "d" -> value * 24 * 60 * 60_000L
            else -> return null
        }
        return millis
    }
}
