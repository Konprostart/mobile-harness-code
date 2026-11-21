package com.mobileharness.code.runtime

import com.mobileharness.code.model.AgentKind
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.model.providersForAgent

/**
 * One provider/model step in an automatic rotation chain. [kind] identifies the provider and
 * [model] the model id to ask that provider for. This is a plain data pair — no API key is ever
 * held here. Keys stay in `ApiKeyVault` and are resolved by the bridge at launch time, so a
 * rotation chain can never leak or duplicate a secret.
 */
data class ProviderRotationCandidate(
    val kind: ProviderKind,
    val model: String,
)

/**
 * An ordered, provider-agnostic fallback chain for one coding agent, resolved once at the start
 * of a turn. When [enabled] is false (the default) the plan is [DISABLED] and rotation is inert,
 * so the existing transport-recovery behaviour is preserved bit-for-bit.
 */
data class ProviderRotationPlan(
    val agent: AgentKind,
    val candidates: List<ProviderRotationCandidate>,
    val enabled: Boolean,
    val maxAttempts: Int,
) {
    companion object {
        val DISABLED = ProviderRotationPlan(AgentKind.CLAUDE_CODE, emptyList(), enabled = false, maxAttempts = 0)
    }

    val isActive: Boolean get() = enabled && candidates.isNotEmpty() && maxAttempts > 0
}

object ProviderRotation {
    /**
     * Default fallback chains. The primary (currently selected) candidate is prepended by [plan],
     * so the returned list describes the whole chain starting with the user's own model. Only
     * providers a coding agent can actually use (see [providersForAgent]) and providers with a
     * saved key survive [plan]. `CUSTOM`, `NVIDIA_NIM` and `OPENCODE_ZEN` are intentionally absent
     * from the *alternatives*: they need extra setup (base URL, wire protocol) a rotation chain
     * cannot assume without the user's own configuration.
     */
    fun defaultCandidates(agent: AgentKind, primary: ProviderProfile): List<ProviderRotationCandidate> {
        if (agent == AgentKind.ANTIGRAVITY) return emptyList()
        val primaryCandidate = ProviderRotationCandidate(primary.kind, primary.model)
        val alternatives = when (agent) {
            AgentKind.DEEPSEEK_HARNESS -> listOf(
                ProviderRotationCandidate(ProviderKind.DEEPSEEK, "deepseek-v4-flash"),
                ProviderRotationCandidate(ProviderKind.DEEPSEEK, "deepseek-v4-pro"),
                ProviderRotationCandidate(ProviderKind.DEEPSEEK, "deepseek-reasoner"),
                ProviderRotationCandidate(ProviderKind.ANTHROPIC, "claude-sonnet-4-6"),
                ProviderRotationCandidate(ProviderKind.ANTHROPIC, "claude-haiku-4-6"),
                ProviderRotationCandidate(ProviderKind.LLM_ROUTER, "anthropic/claude-sonnet-4.6"),
            )
            AgentKind.CLAUDE_CODE -> listOf(
                ProviderRotationCandidate(ProviderKind.ANTHROPIC, "claude-sonnet-4-6"),
                ProviderRotationCandidate(ProviderKind.ANTHROPIC, "claude-haiku-4-6"),
                ProviderRotationCandidate(ProviderKind.ANTHROPIC, "claude-opus-4-6"),
                ProviderRotationCandidate(ProviderKind.DEEPSEEK, "deepseek-v4-flash"),
                ProviderRotationCandidate(ProviderKind.DEEPSEEK, "deepseek-v4-pro"),
                ProviderRotationCandidate(ProviderKind.LLM_ROUTER, "anthropic/claude-sonnet-4.6"),
            )
            else -> emptyList()
        }
        return (listOf(primaryCandidate) + alternatives).distinct()
    }

    /**
     * Resolves a rotation plan for a turn. Candidates are filtered fail-closed to kinds that both
     * have a saved key ([availableKinds]) and are valid for the agent ([providersForAgent]), so the
     * chain can never route to an unauthenticated or unsupported provider.
     */
    fun plan(
        agent: AgentKind,
        primary: ProviderProfile,
        availableKinds: Set<ProviderKind>,
        enabled: Boolean,
        maxAttempts: Int,
        customOrder: List<ProviderRotationCandidate> = emptyList(),
    ): ProviderRotationPlan {
        if (!enabled || agent == AgentKind.ANTIGRAVITY) {
            return ProviderRotationPlan(agent, emptyList(), enabled = false, maxAttempts = 0)
        }
        val ordered = if (customOrder.isNotEmpty()) customOrder else defaultCandidates(agent, primary)
        val validForAgent = providersForAgent(agent).toSet()
        val candidates = ordered
            .filter { it.kind in availableKinds && it.kind in validForAgent }
            .distinct()
        return ProviderRotationPlan(agent, candidates, enabled = true, maxAttempts = maxAttempts.coerceAtLeast(1))
    }

    /**
     * Returns the candidate for the given zero-based [attemptIndex], or null when the chain is
     * inactive or exhausted. The caller treats null as "keep the primary", which keeps rotation
     * fail-closed and never lets a half-configured chain block recovery.
     */
    fun candidateAt(plan: ProviderRotationPlan, attemptIndex: Int): ProviderRotationCandidate? {
        if (!plan.isActive) return null
        if (attemptIndex < 0 || attemptIndex >= plan.maxAttempts) return null
        return plan.candidates.getOrNull(attemptIndex)
    }

    /**
     * Builds the effective [ProviderProfile] for a candidate. The same kind keeps the primary's
     * base URL and per-provider settings (only the model changes); a different kind resolves to
     * that kind's documented defaults so the bridge reaches the right endpoint.
     */
    fun profileFor(primary: ProviderProfile, candidate: ProviderRotationCandidate): ProviderProfile {
        if (candidate.kind == primary.kind) return primary.copy(model = candidate.model)
        return ProviderProfile(kind = candidate.kind, model = candidate.model)
    }
}
