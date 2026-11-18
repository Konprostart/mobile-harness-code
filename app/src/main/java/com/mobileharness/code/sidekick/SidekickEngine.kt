package com.mobileharness.code.sidekick

import android.content.Context
import com.mobileharness.code.R
import com.mobileharness.code.data.ApiKeyVault
import com.mobileharness.code.data.AppPreferences
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.network.ChatCompletion
import com.mobileharness.code.network.ProviderApiClient
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

    suspend fun ask(question: String): SidekickResult =
        complete(systemPrompt = SYSTEM_PROMPT, userMessage = question.trim())

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
        val context = SidekickResearch.gatherContext(sources)
        val userMessage = buildString {
            append("Question: ").append(question.trim()).append('\n')
            append("Allowed sources: ").append(
                sources.joinToString(", ") { it.value },
            ).append('\n')
            append("Context found:\n").append(context.ifBlank { "(nothing readable was found)" })
        }
        return complete(systemPrompt = RESEARCH_PROMPT, userMessage = userMessage)
    }

    private suspend fun complete(systemPrompt: String, userMessage: String): SidekickResult {
        val profile = resolveProfile()
        if (profile.kind == ProviderKind.CLAUDE) {
            return SidekickResult(AppStrings.get(appContext, R.string.sidekick_requires_key))
        }
        val key = vault.get(profile.kind.name)
        if (key.isNullOrBlank()) {
            return SidekickResult(AppStrings.get(appContext, R.string.sidekick_requires_key))
        }
        return when (val result = client.complete(
            baseUrl = profile.resolvedBaseUrl,
            model = profile.model,
            apiKey = key,
            protocol = profile.kind.protocol,
            systemPrompt = systemPrompt,
            userMessage = userMessage,
        )) {
            is ChatCompletion.Success -> SidekickResult(result.text, result.reasoning)
            is ChatCompletion.Failure -> SidekickResult(result.message)
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
