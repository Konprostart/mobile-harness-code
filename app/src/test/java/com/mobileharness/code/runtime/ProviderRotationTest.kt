package com.mobileharness.code.runtime

import com.mobileharness.code.model.AgentKind
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.network.CompletionFailureKind
import com.mobileharness.code.network.classifyCompletionFailure
import com.mobileharness.code.network.classifyFailureKindFromReason
import com.mobileharness.code.network.isRotatableProviderFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRotationTest {
    private val dshPrimary = ProviderProfile(ProviderKind.DEEPSEEK, model = "deepseek-chat")
    private val claudePrimary = ProviderProfile(ProviderKind.ANTHROPIC, model = "claude-sonnet-4-6")

    @Test
    fun defaultCandidatesPrependsPrimary() {
        val candidates = ProviderRotation.defaultCandidates(AgentKind.DEEPSEEK_HARNESS, dshPrimary)
        assertEquals(ProviderRotationCandidate(ProviderKind.DEEPSEEK, "deepseek-chat"), candidates.first())
    }

    @Test
    fun planFiltersUnavailableAndInvalidKinds() {
        val available = setOf(ProviderKind.DEEPSEEK, ProviderKind.ANTHROPIC)
        val plan = ProviderRotation.plan(
            AgentKind.DEEPSEEK_HARNESS, dshPrimary, available, enabled = true, maxAttempts = 4,
        )
        assertTrue(plan.isActive)
        assertTrue(plan.candidates.isNotEmpty())
        plan.candidates.forEach { candidate ->
            assertTrue("candidate kind must be available", candidate.kind in available)
            assertTrue("candidate kind must be valid for agent", candidate.kind in available)
        }
        // OpenRouter is absent from availableKinds, so it must never appear.
        assertFalse(plan.candidates.any { it.kind == ProviderKind.LLM_ROUTER })
    }

    @Test
    fun disabledPlanIsInactive() {
        val plan = ProviderRotation.plan(
            AgentKind.DEEPSEEK_HARNESS, dshPrimary, setOf(ProviderKind.DEEPSEEK),
            enabled = false, maxAttempts = 4,
        )
        assertFalse(plan.isActive)
        assertTrue(plan.candidates.isEmpty())
    }

    @Test
    fun antigravityHasNoChain() {
        val candidates = ProviderRotation.defaultCandidates(AgentKind.ANTIGRAVITY, claudePrimary)
        assertTrue(candidates.isEmpty())
        val plan = ProviderRotation.plan(
            AgentKind.ANTIGRAVITY, claudePrimary, setOf(ProviderKind.ANTHROPIC),
            enabled = true, maxAttempts = 4,
        )
        assertFalse(plan.isActive)
    }

    @Test
    fun candidateAtRespectsMaxAttempts() {
        val plan = ProviderRotation.plan(
            AgentKind.CLAUDE_CODE, claudePrimary,
            setOf(ProviderKind.ANTHROPIC, ProviderKind.DEEPSEEK), enabled = true, maxAttempts = 2,
        )
        assertNotNull(ProviderRotation.candidateAt(plan, 0))
        assertNotNull(ProviderRotation.candidateAt(plan, 1))
        assertNull(ProviderRotation.candidateAt(plan, 2))
        assertNull(ProviderRotation.candidateAt(plan, -1))
    }

    @Test
    fun candidateAtNullWhenInactive() {
        val plan = ProviderRotationPlan(AgentKind.CLAUDE_CODE, emptyList(), enabled = false, maxAttempts = 0)
        assertNull(ProviderRotation.candidateAt(plan, 0))
    }

    @Test
    fun profileForSameKindKeepsBaseUrl() {
        val primary = ProviderProfile(ProviderKind.DEEPSEEK, baseUrl = "https://proxy.example/anthropic", model = "deepseek-chat")
        val candidate = ProviderRotationCandidate(ProviderKind.DEEPSEEK, "deepseek-reasoner")
        val profile = ProviderRotation.profileFor(primary, candidate)
        assertEquals(ProviderKind.DEEPSEEK, profile.kind)
        assertEquals("deepseek-reasoner", profile.model)
        assertEquals("https://proxy.example/anthropic", profile.baseUrl)
    }

    @Test
    fun profileForDifferentKindUsesDefaults() {
        val candidate = ProviderRotationCandidate(ProviderKind.ANTHROPIC, "claude-sonnet-4-6")
        val profile = ProviderRotation.profileFor(dshPrimary, candidate)
        assertEquals(ProviderKind.ANTHROPIC, profile.kind)
        assertEquals("claude-sonnet-4-6", profile.model)
        assertEquals(ProviderKind.ANTHROPIC.defaultBaseUrl, profile.baseUrl)
    }

    @Test
    fun classify402IsQuotaExceeded() {
        assertEquals(CompletionFailureKind.QUOTA_EXCEEDED, classifyCompletionFailure(402, null))
    }

    @Test
    fun classifyReasonMapsTransientKinds() {
        assertEquals(CompletionFailureKind.RATE_LIMITED, classifyFailureKindFromReason("rate limit exceeded"))
        assertEquals(CompletionFailureKind.QUOTA_EXCEEDED, classifyFailureKindFromReason("quota exceeded"))
        assertEquals(CompletionFailureKind.NETWORK, classifyFailureKindFromReason("connection error"))
        assertEquals(CompletionFailureKind.TIMEOUT, classifyFailureKindFromReason("read timed out"))
        assertEquals(CompletionFailureKind.AUTH, classifyFailureKindFromReason("the provider rejected the saved API key"))
        assertEquals(CompletionFailureKind.OTHER, classifyFailureKindFromReason("DeepSeek Harness stopped with exit code 1"))
    }

    @Test
    fun classifyReasonMapsServerErrors() {
        assertEquals(CompletionFailureKind.SERVER_ERROR, classifyFailureKindFromReason("server error"))
        assertEquals(CompletionFailureKind.SERVER_ERROR, classifyFailureKindFromReason("502 Bad Gateway"))
        assertEquals(CompletionFailureKind.SERVER_ERROR, classifyFailureKindFromReason("internal server error (503)"))
        assertEquals(CompletionFailureKind.SERVER_ERROR, classifyFailureKindFromReason("upstream returned 500"))
    }

    @Test
    fun isRotatableProviderFailureOnlyMatchesProviderRejections() {
        assertTrue(isRotatableProviderFailure("rate limit exceeded"))
        assertTrue(isRotatableProviderFailure("quota exceeded"))
        assertTrue(isRotatableProviderFailure("502 Bad Gateway"))
        assertFalse(isRotatableProviderFailure("connection error"))
        assertFalse(isRotatableProviderFailure("read timed out"))
        assertFalse(isRotatableProviderFailure("the provider rejected the saved API key"))
        assertFalse(isRotatableProviderFailure("DeepSeek Harness stopped with exit code 1"))
    }
}
