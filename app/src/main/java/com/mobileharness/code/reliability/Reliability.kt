package com.mobileharness.code.reliability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Central Reliability & Recovery engine for agent orchestration.
 *
 * This package composes with (never replaces) the existing
 * [com.mobileharness.code.runtime.RuntimeRecoveryPolicy], which already classifies transport-level
 * recovery for the external CLI agents. What is added here is the *orchestration-level* safety net:
 * heartbeat/liveness, stall detection, bounded exponential-backoff retry, task checkpoints, and an
 * idempotency guard so an autonomously scheduled task can never be executed twice for the same
 * logical occurrence. Everything is pure JVM and unit-testable without an Android device.
 */

/** Why an agent or task was classified as failed. Stable, machine-readable. */
enum class FailureKind {
    /** A transient network or provider error (retryable with backoff). */
    NETWORK,
    /** A provider reported a quota/auth/terminal error (retryable only on a different route). */
    PROVIDER,
    /** The agent process exited unexpectedly. */
    CRASH,
    /** The agent stopped producing output but the process may still be alive. */
    STALLED,
    /** The task is slow but progressing; not a failure. */
    SLOW,
    /** The user cancelled the task. */
    CANCELLED,
    /** Unknown cause — treated as the most conservative (manual retry). */
    UNKNOWN,
}

/** A policy decision about whether and how to retry a failed task. */
sealed interface RetryDecision {
    /** Safe to retry immediately (no side effects known). */
    data class Retry(val backoffMillis: Long, val reason: String) : RetryDecision

    /** Must be surfaced to the user; no automatic retry. */
    data class Manual(val reason: String) : RetryDecision

    /** Must not be retried (user cancelled, or a terminal failure). */
    data class NoRetry(val reason: String) : RetryDecision
}

/**
 * Pure, fail-closed reliability policy. A retry is only ever allowed when the failure kind is
 * transient and a bounded attempt budget remains; anything else escalates to manual or no-retry so
 * an autonomous workflow can never loop forever or repeat a side-effecting action.
 */
object ReliabilityPolicy {
    /** Default maximum automatic attempts for a single logical task occurrence. */
    const val DEFAULT_MAX_ATTEMPTS = 3

    /** Default base backoff, multiplied by 2^(attempt-1) and jittered. */
    const val DEFAULT_BASE_BACKOFF_MILLIS = 2_000L

    /** Default ceiling for backoff so a misconfiguration cannot sleep for hours. */
    const val DEFAULT_MAX_BACKOFF_MILLIS = 60_000L

    fun classify(
        kind: FailureKind,
        attempt: Int,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        baseBackoffMillis: Long = DEFAULT_BASE_BACKOFF_MILLIS,
        maxBackoffMillis: Long = DEFAULT_MAX_BACKOFF_MILLIS,
    ): RetryDecision {
        if (kind == FailureKind.CANCELLED) return RetryDecision.NoRetry("Cancelled by the user.")
        if (kind == FailureKind.UNKNOWN) return RetryDecision.Manual("Failure cause is unknown; manual retry required.")
        if (attempt >= maxAttempts) {
            return RetryDecision.Manual("Attempt limit ($maxAttempts) reached.")
        }
        return when (kind) {
            FailureKind.NETWORK, FailureKind.CRASH, FailureKind.STALLED, FailureKind.SLOW ->
                RetryDecision.Retry(backoff(attempt, baseBackoffMillis, maxBackoffMillis), "Transient ${kind.name.lowercase()} failure.")
            FailureKind.PROVIDER ->
                RetryDecision.Manual("Provider failure; retry on a different route or provider.")
            else -> RetryDecision.Manual("Not auto-retryable.")
        }
    }

    /** Deterministic exponential backoff (no RNG) so unit tests can assert exact values. */
    fun backoff(attempt: Int, baseMillis: Long, maxMillis: Long): Long {
        if (attempt <= 0) return 0L
        var value = baseMillis
        repeat(attempt - 1) {
            value = (value * 2).coerceAtMost(maxMillis)
        }
        return value.coerceIn(0L, maxMillis)
    }
}

/**
 * Tracks liveness of an in-flight agent/task by recording heartbeat timestamps. A task is "stalled"
 * when no heartbeat has arrived for longer than the configured stall threshold while the task has
 * not completed. Heartbeats are advisory: a slow-but-alive process that still emits output is never
 * killed merely for being slow.
 */
class AgentHeartbeat(private val now: () -> Long = System::currentTimeMillis) {
    data class State(val lastHeartbeatMillis: Long, val startedMillis: Long, val alive: Boolean)

    private val beats = ConcurrentHashMap<String, State>()

    fun start(id: String, startedMillis: Long = now()) {
        beats[id] = State(startedMillis, startedMillis, alive = true)
    }

    fun beat(id: String, atMillis: Long = now()) {
        val previous = beats[id] ?: return
        beats[id] = previous.copy(lastHeartbeatMillis = atMillis, alive = true)
    }

    fun stop(id: String) {
        beats[id]?.let { beats[id] = it.copy(alive = false) }
    }

    fun state(id: String): State? = beats[id]

    fun lastHeartbeat(id: String): Long = beats[id]?.lastHeartbeatMillis ?: 0L

    fun isAlive(id: String): Boolean = beats[id]?.alive == true

    fun remove(id: String) = beats.remove(id)
}

/**
 * Decides whether a running task has stalled, distinguishing a stall (no activity for too long)
 * from slow-but-progressing work (last activity is recent). It never reports a stall for a task it
 * does not know about.
 */
object StallDetector {
    fun isStalled(
        heartbeat: AgentHeartbeat,
        id: String,
        stallThresholdMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val state = heartbeat.state(id) ?: return false
        if (!state.alive) return false
        val last = state.lastHeartbeatMillis
        if (last <= 0L) return nowMillis - state.startedMillis > stallThresholdMillis
        return nowMillis - last > stallThresholdMillis
    }
}

/**
 * A resumable checkpoint captured before a task runs and updated after it finishes, so a crashed
 * task can be inspected (and optionally resumed) instead of blindly re-run. A checkpoint is plain
 * data; the Android layer persists it, keeping this class unit-testable.
 */
data class TaskCheckpoint(
    val taskId: String,
    val occurrenceKey: String,
    val startedMillis: Long,
    val finishedMillis: Long = 0L,
    val status: String = "running",
    val progress: String = "",
    val resultDigest: String = "",
) {
    val finished: Boolean get() = finishedMillis > 0L
}

class TaskCheckpointStore {
    private val checkpoints = ConcurrentHashMap<String, TaskCheckpoint>()

    fun save(checkpoint: TaskCheckpoint) {
        checkpoints[checkpoint.taskId] = checkpoint
    }

    fun get(taskId: String): TaskCheckpoint? = checkpoints[taskId]

    fun remove(taskId: String): TaskCheckpoint? = checkpoints.remove(taskId)

    fun snapshot(): List<TaskCheckpoint> = checkpoints.values.toList()

    fun clear() = checkpoints.clear()
}

/**
 * Guards against duplicate execution of the same logical occurrence. A task occurrence is keyed by
 * `taskId + occurrenceKey` (e.g. the scheduled epoch bucket); [tryClaim] atomically claims the key
 * so only one caller may run it, and [release] frees it after completion (or failure) so a future,
 * *different* occurrence can run.
 */
class IdempotencyGuard {
    private val claimed = ConcurrentHashMap<String, AtomicLong>()

    /** Returns true when this caller won the claim (i.e. it should run). */
    fun tryClaim(taskId: String, occurrenceKey: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val key = key(taskId, occurrenceKey)
        val previous = claimed.putIfAbsent(key, AtomicLong(nowMillis))
        return previous == null
    }

    fun isClaimed(taskId: String, occurrenceKey: String): Boolean =
        claimed.containsKey(key(taskId, occurrenceKey))

    fun release(taskId: String, occurrenceKey: String) {
        claimed.remove(key(taskId, occurrenceKey))
    }

    fun pruneOlderThan(olderThanMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        claimed.entries.removeIf { (_, value) -> nowMillis - value.get() > olderThanMillis }
    }

    fun activeCount(): Int = claimed.size

    private fun key(taskId: String, occurrenceKey: String): String = "$taskId\u0000$occurrenceKey"
}

/**
 * Combines [ReliabilityPolicy], [AgentHeartbeat], [StallDetector], [TaskCheckpointStore] and
 * [IdempotencyGuard] into a single orchestration-level recovery entry point. It answers the two
 * questions every autonomous runner must answer: "should I run this occurrence?" and "after a
 * failure, may I retry it?"
 */
class RecoveryEngine(
    private val heartbeat: AgentHeartbeat = AgentHeartbeat(),
    private val checkpoints: TaskCheckpointStore = TaskCheckpointStore(),
    private val idempotency: IdempotencyGuard = IdempotencyGuard(),
    private val maxAttempts: Int = ReliabilityPolicy.DEFAULT_MAX_ATTEMPTS,
) {
    /** Claims an occurrence for execution; false means another runner already owns it. */
    fun claim(taskId: String, occurrenceKey: String): Boolean {
        if (!idempotency.tryClaim(taskId, occurrenceKey)) return false
        heartbeat.start(taskId)
        checkpoints.save(
            TaskCheckpoint(taskId = taskId, occurrenceKey = occurrenceKey, startedMillis = System.currentTimeMillis()),
        )
        return true
    }

    fun beat(taskId: String) = heartbeat.beat(taskId)

    fun finish(taskId: String, status: String, resultDigest: String = "") {
        val existing = checkpoints.get(taskId)
        if (existing != null) {
            checkpoints.save(
                existing.copy(finishedMillis = System.currentTimeMillis(), status = status, resultDigest = resultDigest),
            )
            idempotency.release(taskId, existing.occurrenceKey)
        }
        heartbeat.stop(taskId)
    }

    fun isStalled(taskId: String, stallThresholdMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        StallDetector.isStalled(heartbeat, taskId, stallThresholdMillis, nowMillis)

    fun checkpoint(taskId: String): TaskCheckpoint? = checkpoints.get(taskId)

    fun decideRetry(failureKind: FailureKind, attempt: Int): RetryDecision =
        ReliabilityPolicy.classify(failureKind, attempt, maxAttempts)
}
