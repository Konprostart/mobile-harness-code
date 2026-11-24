package com.mobileharness.code.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {

    @Test
    fun backoff_growsExponentiallyAndCaps() {
        assertEquals(0L, ReliabilityPolicy.backoff(0, 2_000, 60_000))
        assertEquals(2_000L, ReliabilityPolicy.backoff(1, 2_000, 60_000))
        assertEquals(4_000L, ReliabilityPolicy.backoff(2, 2_000, 60_000))
        assertEquals(8_000L, ReliabilityPolicy.backoff(3, 2_000, 60_000))
        assertEquals(60_000L, ReliabilityPolicy.backoff(10, 2_000, 60_000))
    }

    @Test
    fun classify_networkFailureIsRetryableWithinBudget() {
        val decision = ReliabilityPolicy.classify(FailureKind.NETWORK, attempt = 1)
        assertTrue(decision is RetryDecision.Retry)
    }

    @Test
    fun classify_attemptLimitEscalatesToManual() {
        val decision = ReliabilityPolicy.classify(FailureKind.NETWORK, attempt = 3, maxAttempts = 3)
        assertTrue(decision is RetryDecision.Manual)
    }

    @Test
    fun classify_unknownAndCancelledNeverRetry() {
        assertTrue(ReliabilityPolicy.classify(FailureKind.UNKNOWN, 0) is RetryDecision.Manual)
        assertTrue(ReliabilityPolicy.classify(FailureKind.CANCELLED, 0) is RetryDecision.NoRetry)
    }

    @Test
    fun heartbeat_recordsLiveness() {
        var now = 1_000L
        val heartbeat = AgentHeartbeat { now }
        heartbeat.start("task")
        heartbeat.beat("task")
        now = 2_000L
        heartbeat.beat("task")
        assertEquals(2_000L, heartbeat.lastHeartbeat("task"))
        assertTrue(heartbeat.isAlive("task"))
        heartbeat.stop("task")
        assertFalse(heartbeat.isAlive("task"))
    }

    @Test
    fun stallDetector_distinguishesStallFromProgress() {
        var now = 10_000L
        val heartbeat = AgentHeartbeat { now }
        heartbeat.start("slow")
        heartbeat.beat("slow")
        // 5s later, under a 10s threshold → not stalled
        now = 15_000L
        assertFalse(StallDetector.isStalled(heartbeat, "slow", 10_000L, now))
        // 11s later, over the threshold → stalled
        now = 21_000L
        assertTrue(StallDetector.isStalled(heartbeat, "slow", 10_000L, now))
    }

    @Test
    fun stallDetector_unknownTaskNeverStalled() {
        val heartbeat = AgentHeartbeat { 1L }
        assertFalse(StallDetector.isStalled(heartbeat, "missing", 1_000L))
    }

    @Test
    fun idempotencyGuard_singleClaimOnly() {
        val guard = IdempotencyGuard()
        assertTrue(guard.tryClaim("task", "occ-1"))
        assertFalse(guard.tryClaim("task", "occ-1"))
        assertTrue(guard.tryClaim("task", "occ-2"))
        guard.release("task", "occ-1")
        assertTrue(guard.tryClaim("task", "occ-1"))
    }

    @Test
    fun checkpointStore_roundTrips() {
        val store = TaskCheckpointStore()
        store.save(TaskCheckpoint("task", "occ-1", startedMillis = 5L))
        val loaded = store.get("task")
        assertNotNull(loaded)
        assertFalse(loaded!!.finished)
        assertEquals("running", loaded.status)
        store.save(loaded.copy(finishedMillis = 9L, status = "success"))
        assertTrue(store.get("task")!!.finished)
    }

    @Test
    fun recoveryEngine_claimAndFinishLifecycle() {
        val engine = RecoveryEngine()
        assertTrue(engine.claim("task", "occ-1"))
        assertFalse(engine.claim("task", "occ-1"))
        engine.beat("task")
        engine.finish("task", "success", "digest")
        val checkpoint = engine.checkpoint("task")
        assertNotNull(checkpoint)
        assertEquals("success", checkpoint!!.status)
        // After finish, a *different* occurrence may run again.
        assertTrue(engine.claim("task", "occ-2"))
    }
}
