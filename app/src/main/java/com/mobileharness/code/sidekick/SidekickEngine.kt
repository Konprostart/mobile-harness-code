package com.mobileharness.code.sidekick

import android.content.Context
import com.mobileharness.code.R
import com.mobileharness.code.data.ApiKeyVault
import com.mobileharness.code.data.AppPreferences
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.network.ChatCompletion
import com.mobileharness.code.network.CompletionFailureKind
import com.mobileharness.code.network.CompletionImage
import com.mobileharness.code.network.FallbackChain
import com.mobileharness.code.network.ProviderApiClient
import com.mobileharness.code.network.RouteAttempt
import com.mobileharness.code.network.RouteBudget
import com.mobileharness.code.network.RouteLog
import com.mobileharness.code.network.RouteLogEntry
import com.mobileharness.code.network.RouteOutcome
import com.mobileharness.code.runtime.EcoMode
import com.mobileharness.code.ui.AppStrings

/** Answer from the assistant: final text plus an optional chain-of-thought excerpt. */
data class SidekickResult(val text: String, val reasoning: String? = null)

/**
 * Backend for the in-app "Sidekick" assistant. It answers questions, helps optimize prompts and
 * runs bounded background research using the provider the user already configured for their
 * coding agent — or an independent, more powerful model when the user opted into one.
 *
 * The engine never stores a key: it reads the same encrypted [ApiKeyVault] entry for the selected
 * provider kind, and it falls back to a short guidance message when the configured provider is
 * OAuth-only (Claude subscription) or has no API key.
 */
class SidekickEngine(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val vault = ApiKeyVault(appContext)
    private val client = ProviderApiClient(appContext)

    /** Resolves the profile the assistant should use, honoring the independent-model opt-in. */
    fun resolveProfile(): ProviderProfile {
        val fallback = preferences.loadProvider(vault)
        return if (preferences.sidekickUseCustomProvider) {
            preferences.loadSidekickProvider(vault, fallback)
        } else {
            fallback
        }
    }

    suspend fun ask(
        question: String,
        images: List<SidekickImage> = emptyList(),
        files: List<SidekickFile> = emptyList(),
    ): SidekickResult {
        val userMessage = SidekickInput.buildUserMessage(question, files)
        return complete(
            systemPrompt = SYSTEM_PROMPT,
            userMessage = userMessage,
            images = images,
        )
    }

    suspend fun optimizePrompt(draft: String): SidekickResult =
        complete(systemPrompt = PROMPT_ADVISOR_PROMPT, userMessage = draft.trim())

    suspend fun research(question: String): SidekickResult {
        val rawSources = preferences.sidekickResearchSources
        if (!preferences.sidekickBackgroundResearch) {
            return SidekickResult(AppStrings.get(appContext, R.string.sidekick_research_disabled))
        }
        val sources = SidekickResearch.parseSources(rawSources)
        if (sources.isEmpty()) {
            return SidekickResult(AppStrings.get(appContext, R.string.sidekick_research_no_sources))
        }
        val eco = preferences.ecoModeEnabled
        val context = SidekickResearch.gatherContext(
            sources,
            maxFiles = EcoMode.researchFileLimit(eco),
            maxTotalBytes = EcoMode.researchByteLimit(eco),
        )
        val userMessage = buildString {
            append("Question: ").append(question.trim()).append('\n')
            append("Allowed sources: ").append(
                sources.joinToString(", ") { it.value },
            ).append('\n')
            append("Context found:\n").append(context.ifBlank { "(nothing readable was found)" })
        }
        return complete(systemPrompt = RESEARCH_PROMPT, userMessage = userMessage)
    }

    /** Resolves the local model config from preferences, applying the default model name when blank. */
    private fun localModelConfig(): LocalModelConfig =
        LocalModelConfig(
            enabled = preferences.localModelEnabled,
            baseUrl = preferences.localModelBaseUrl.ifBlank { LocalModelConfig.DEFAULT_BASE_URL },
            model = preferences.localModelName.ifBlank { LocalModelConfig.DEFAULT_MODEL },
        )

    private suspend fun complete(
        systemPrompt: String,
        userMessage: String,
        images: List<SidekickImage> = emptyList(),
    ): SidekickResult {
        val profile = resolveProfile()
        if (profile.kind == ProviderKind.CLAUDE) {
            return SidekickResult(AppStrings.get(appContext, R.string.sidekick_requires_key))
        }
        val key = vault.get(profile.kind.name)
        if (key.isNullOrBlank()) {
            return SidekickResult(AppStrings.get(appContext, R.string.sidekick_requires_key))
        }

        val ecoEnabled = preferences.ecoModeEnabled
        val maxTokensPerCall = EcoMode.recommendedMaxTokens(ecoEnabled)
        val local = localModelConfig()
        val routes = buildSidekickRoutes(
            primaryModel = profile.model,
            fallbackEnabled = preferences.sidekickFallbackEnabled,
            fallbackModel = preferences.sidekickFallbackModel,
            local = local,
        )
        // A small, self-documenting cap bounds the fallback loop: up to 4 attempts and a token
        // ceiling of four times the per-call budget. On exhaustion the chain halts fail-closed.
        val budget = RouteBudget(maxRequests = 4, maxTokens = maxTokensPerCall.toLong() * 4L)
        val chain = FallbackChain(
            routes = routes,
            budget = budget,
            mutating = false,
            ecoEnabled = ecoEnabled,
            onEvent = { event ->
                RouteLog.record(
                    RouteLogEntry(
                        timestampMillis = System.currentTimeMillis(),
                        routeId = event.routeId,
                        model = event.model,
                        stage = event.stage,
                        detail = event.detail,
                    ),
                )
            },
        ).also { it.tokensPerAttempt = maxTokensPerCall.toLong() }

        return when (val outcome = chain.run { route ->
            val target = resolveSidekickRoute(route, profile, key, local)
            when (val result = client.complete(
                baseUrl = target.baseUrl,
                model = route.model,
                apiKey = target.apiKey,
                protocol = target.protocol,
                systemPrompt = systemPrompt,
                userMessage = userMessage,
                maxTokens = maxTokensPerCall,
                images = images.map { CompletionImage(it.mimeType, it.base64) },
            )) {
                is ChatCompletion.Success -> RouteAttempt.Success(result.text, result.reasoning)
                is ChatCompletion.Failure -> RouteAttempt.Failure(result.kind, result.message)
            }
        }) {
            is RouteOutcome.Success -> SidekickResult(outcome.text, outcome.reasoning)
            is RouteOutcome.Failure -> when (outcome.kind) {
                CompletionFailureKind.AUTH -> SidekickResult(outcome.message)
                else -> SidekickResult(
                    if (routes.size > 1 && outcome.routeId == routes.last().id) {
                        AppStrings.get(appContext, R.string.sidekick_fallback_exhausted, outcome.message)
                    } else {
                        outcome.message
                    },
                )
            }
        }
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You are the Mobile Harness Code assistant, a concise helper for an Android app that
            builds Android apps directly on the phone, runs coding agents (Claude Code, DeepSeek
            Harness, Antigravity), provides a Linux terminal (PRoot/Ubuntu), and encrypted backup.
            Answer the user's question briefly and accurately. Do not invent features. Keep
            answers under ~120 words unless asked for detail.
        """.trimIndent()

        val PROMPT_ADVISOR_PROMPT = """
            You are a prompt-engineering advisor for coding agents (Claude Code, DeepSeek Harness,
            Antigravity) that build software on an Android phone. The user will paste a prompt they
            intend to send to their coding agent. Rewrite it to be clearer, more specific and more
            effective: state the goal, constraints, desired output and acceptance criteria, and
            split ambiguous parts into smaller steps. Keep the same language as the input. Reply
            with the improved prompt only, followed by a short (2-3 bullet) list of what you changed
            and why.
        """.trimIndent()

        val RESEARCH_PROMPT = """
            You are a research assistant for a user building apps with an on-device coding agent.
            The user provides a question plus text gathered from directories and URLs they explicitly
            allowed. Answer using ONLY the provided context and your own general knowledge; clearly
            say when the provided context is insufficient. Do not fabricate citations. Keep the
            answer focused and under ~250 words unless detail is requested.
        """.trimIndent()
    }
}
