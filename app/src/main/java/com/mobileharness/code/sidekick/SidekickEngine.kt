package com.mobileharness.code.sidekick

import android.content.Context
import com.mobileharness.code.R
import com.mobileharness.code.data.ApiKeyVault
import com.mobileharness.code.data.AppPreferences
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.network.ChatCompletion
import com.mobileharness.code.network.ProviderApiClient
import com.mobileharness.code.ui.AppStrings

/**
 * Backend for the in-app "Sidekick" assistant: a one-shot Q&A that uses the same provider the
 * user already configured for their coding agent (Anthropic, OpenRouter, DeepSeek, custom
 * gateway, …). It never stores a key and falls back to a short guidance message when the
 * configured provider is OAuth-only (Claude subscription) or has no API key.
 */
class SidekickEngine(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val vault = ApiKeyVault(appContext)
    private val client = ProviderApiClient(appContext)

    suspend fun ask(question: String): String {
        val profile = preferences.loadProvider(vault)
        if (profile.kind == ProviderKind.CLAUDE) {
            return AppStrings.get(appContext, R.string.sidekick_requires_key)
        }
        val key = vault.get(profile.kind.name)
        if (key.isNullOrBlank()) {
            return AppStrings.get(appContext, R.string.sidekick_requires_key)
        }
        return when (val result = client.complete(
            baseUrl = profile.resolvedBaseUrl,
            model = profile.model,
            apiKey = key,
            protocol = profile.kind.protocol,
            systemPrompt = SYSTEM_PROMPT,
            userMessage = question.trim(),
        )) {
            is ChatCompletion.Success -> result.text
            is ChatCompletion.Failure -> result.message
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
    }
}
