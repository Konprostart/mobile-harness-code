package com.mobileharness.code.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackChainTest {
    private val primary = ModelRoute("primary", "model-a")
    private val fallback = ModelRoute("fallback", "model-b")

    @Test
    fun classifyCompletionFailureDistinguishesAuthFromTransient() {
        assertEquals(CompletionFailureKind.AUTH, classifyCompletionFailure(401, null))
        assertEquals(CompletionFailureKind.AUTH, classifyCompletionFailure(403, null))
        assertEquals(CompletionFailureKind.RATE_LIMITED, classifyCompletionFailure(429, null))
        assertEquals(CompletionFailureKind.SERVER_ERROR, classifyCompletionFailure(500, null))
        assertEquals(CompletionFailureKind.SERVER_ERROR, classifyCompletionFailure(503, null))
        assertEquals(CompletionFailureKind.BAD_REQUEST, classifyCompletionFailure(400, null))
        assertEquals(CompletionFailureKind.BAD_REQUEST, classifyCompletionFailure(404, null))
        assertEquals(CompletionFailureKind.TIMEOUT, classifyCompletionFailure(0, "read timed out"))
        assertEquals(CompletionFailureKind.NETWORK, classifyCompletionFailure(0, "connection refused"))
    }

    @Test
    fun primarySuccessReturnsWithoutTryingFallback() = runBlocking {
        val attempted = mutableListOf<String>()
        val chain = FallbackChain(listOf(primary, fallback), backoff = { })
        val outcome = chain.run { route ->
            attempted += route.id
            RouteAttempt.Success("ok from ${route.model}")
        }
        assertTrue(outcome is RouteOutcome.Success)
        assertEquals("primary", (outcome as RouteOutcome.Success).routeId)
        assertEquals(listOf("primary"), attempted)
    }

    @Test
    fun rateLimitFallsBackToNextModel() = runBlocking {
        val attempted = mutableListOf<String>()
        val chain = FallbackChain(listOf(primary, fallback), backoff = { })
        val outcome = chain.run { route ->
            attempted += route.id
            if (route.id == "primary") {
                RouteAttempt.Failure(CompletionFailureKind.RATE_LIMITED, "429")
            } else {
                RouteAttempt.Success("ok from fallback")
            }
        }
        assertTrue(outcome is RouteOutcome.Success)
        assertEquals("fallback", (outcome as RouteOutcome.Success).routeId)
        assertEquals("ok from fallback", outcome.text)
        assertEquals(listOf("primary", "fallback"), attempted)
    }

    @Test
    fun serverErrorAndTimeoutBothFallBack() = runBlocking {
        for (kind in listOf(CompletionFailureKind.SERVER_ERROR, CompletionFailureKind.TIMEOUT, CompletionFailureKind.NETWORK)) {
            val chain = FallbackChain(listOf(primary, fallback), backoff = { })
            val outcome = chain.run { route ->
                if (route.id == "primary") RouteAttempt.Failure(kind, "down")
                else RouteAttempt.Success("recovered")
            }
            assertTrue("$kind should fall back", outcome is RouteOutcome.Success)
            assertEquals("fallback", (outcome as RouteOutcome.Success).routeId)
        }
    }

    @Test
    fun authFailureIsFailClosedAndNeverTriesFallback() = runBlocking {
        val attempted = mutableListOf<String>()
        val chain = FallbackChain(listOf(primary, fallback), backoff = { })
        val outcome = chain.run { route ->
            attempted += route.id
            RouteAttempt.Failure(CompletionFailureKind.AUTH, "invalid key")
        }
        assertTrue(outcome is RouteOutcome.Failure)
        assertEquals(CompletionFailureKind.AUTH, (outcome as RouteOutcome.Failure).kind)
        assertEquals("primary", outcome.routeId)
        assertEquals(listOf("primary"), attempted)
    }

    @Test
    fun badRequestIsFailClosed() = runBlocking {
        val chain = FallbackChain(listOf(primary, fallback), backoff = { })
        val outcome = chain.run { RouteAttempt.Failure(CompletionFailureKind.BAD_REQUEST, "unknown model") }
        assertTrue(outcome is RouteOutcome.Failure)
        assertEquals("primary", (outcome as RouteOutcome.Failure).routeId)
    }

    @Test
    fun authFallsBackOnlyWhenExplicitlyEnabled() = runBlocking {
        val chain = FallbackChain(listOf(primary, fallback), backoff = { }, fallbackOnAuth = true)
        val outcome = chain.run { route ->
            if (route.id == "primary") RouteAttempt.Failure(CompletionFailureKind.AUTH, "bad")
            else RouteAttempt.Success("ok")
        }
        assertTrue(outcome is RouteOutcome.Success)
        assertEquals("fallback", (outcome as RouteOutcome.Success).routeId)
    }

    @Test
    fun mutatingChainNeverFallsBack() = runBlocking {
        val attempted = mutableListOf<String>()
        val chain = FallbackChain(listOf(primary, fallback), mutating = true, backoff = { })
        val outcome = chain.run { route ->
            attempted += route.id
            RouteAttempt.Failure(CompletionFailureKind.RATE_LIMITED, "429")
        }
        assertTrue(outcome is RouteOutcome.Failure)
        assertEquals("primary", (outcome as RouteOutcome.Failure).routeId)
        assertEquals(listOf("primary"), attempted)
    }

    @Test
    fun budgetHaltsFailClosedBeforeNextAttempt() = runBlocking {
        val budget = RouteBudget(maxRequests = 1, maxTokens = 100)
        val chain = FallbackChain(listOf(primary, fallback), budget = budget, backoff = { })
        chain.tokensPerAttempt = 50
        val events = mutableListOf<RouteEvent>()
        val chainWithEvents = FallbackChain(listOf(primary, fallback), budget = budget, backoff = { }, onEvent = { events += it })
        chainWithEvents.tokensPerAttempt = 50
        val outcome = chainWithEvents.run { route ->
            RouteAttempt.Failure(CompletionFailureKind.RATE_LIMITED, "429")
        }
        assertTrue(outcome is RouteOutcome.Failure)
        assertTrue(events.any { it.stage == RouteStage.BUDGET_HALTED })
        assertEquals(1, budget.requestCount)
        assertEquals(50L, budget.estimatedTokens)
    }

    @Test
    fun routeBudgetTracksRequestsTokensAndExhaustion() {
        val budget = RouteBudget(maxRequests = 3, maxTokens = 10)
        assertFalse(budget.exhausted)
        budget.recordAttempt(4)
        budget.recordAttempt(4)
        assertEquals(2, budget.requestCount)
        assertEquals(8L, budget.estimatedTokens)
        assertFalse(budget.exhausted)
        budget.recordAttempt(4)
        assertEquals(12L, budget.estimatedTokens)
        assertTrue(budget.exhausted)
    }

    @Test
    fun backoffDelayIsCappedAndNonNegative() {
        var previous = -1L
        for (attempt in 0..8) {
            val delay = FallbackPolicy.backoffDelayMillis(attempt, ecoEnabled = false)
            assertTrue(delay >= 0L)
            assertTrue(delay <= 30_000L)
            if (attempt > 0) assertTrue(delay >= previous)
            previous = delay
        }
    }

    @Test
    fun routeLogIsCappedAndClearable() {
        RouteLog.clear()
        repeat(150) { i ->
            RouteLog.record(RouteLogEntry(i.toLong(), "r$i", "m$i", RouteStage.ATTEMPT, null))
        }
        assertTrue(RouteLog.snapshot().size <= 100)
        RouteLog.clear()
        assertTrue(RouteLog.snapshot().isEmpty())
    }
}
