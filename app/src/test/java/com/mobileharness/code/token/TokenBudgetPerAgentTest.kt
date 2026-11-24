package com.mobileharness.code.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBudgetPerAgentTest {

    @Test
    fun budgetCodecRoundTripsPerAgentAndProject() {
        val config = BudgetConfig(
            dailyLimit = 1.0,
            perAgentLimits = mapOf("claude-code" to 2.0, "sidekick" to 0.5),
            perProjectLimits = mapOf("project-1" to 3.0),
            warnAtPercent = 90,
            reachedAction = BudgetReachedAction.STOP_NEW_REQUESTS,
        )
        val decoded = BudgetConfigCodec.fromJson(BudgetConfigCodec.toJson(config))
        assertEquals(2.0, decoded.perAgentLimits["claude-code"]!!, 1e-9)
        assertEquals(3.0, decoded.perProjectLimits["project-1"]!!, 1e-9)
        assertEquals(90, decoded.warnAtPercent)
        assertEquals(BudgetReachedAction.STOP_NEW_REQUESTS, decoded.reachedAction)
    }

    @Test
    fun evaluateIncludesPerAgentAndProject() {
        val config = BudgetConfig(
            perAgentLimits = mapOf("claude-code" to 5.0),
            perProjectLimits = mapOf("project-1" to 10.0),
        )
        // Agent spend reached → REACHED even though other windows are fine.
        val status = BudgetPolicy.evaluate(
            config = config,
            dailySpend = 0.0,
            weeklySpend = 0.0,
            monthlySpend = 0.0,
            perProviderSpend = 0.0,
            perModelSpend = 0.0,
            perAgentSpend = 6.0,
            perProjectSpend = 1.0,
            agent = "claude-code",
            projectId = "project-1",
        )
        assertEquals(BudgetStatus.REACHED, status)
    }

    @Test
    fun evaluateIgnoresMissingAgentLimits() {
        val config = BudgetConfig(dailyLimit = 100.0)
        val status = BudgetPolicy.evaluate(
            config = config,
            dailySpend = 1.0,
            weeklySpend = 0.0,
            monthlySpend = 0.0,
            perProviderSpend = 0.0,
            perModelSpend = 0.0,
            perAgentSpend = 999.0, // no per-agent limit configured → ignored
            agent = "claude-code",
        )
        assertEquals(BudgetStatus.UNDER, status)
    }

    @Test
    fun legacyBudgetJsonStillDecodes() {
        val legacy = """{"daily":5.0,"weekly":null,"monthly":null,"perProvider":{},"perModel":{},"warnAtPercent":80,"reachedAction":"warn"}"""
        val decoded = BudgetConfigCodec.fromJson(legacy)
        assertEquals(5.0, decoded.dailyLimit!!, 1e-9)
        assertTrue(decoded.perAgentLimits.isEmpty())
        assertTrue(decoded.perProjectLimits.isEmpty())
        assertFalse(BudgetPolicy.shouldBlock(BudgetStatus.REACHED, decoded.reachedAction))
    }
}
