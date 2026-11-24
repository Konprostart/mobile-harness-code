package com.mobileharness.code.token

import com.mobileharness.code.model.ProviderProtocol
import org.json.JSONObject

/**
 * Normalized token usage for a single provider request.
 *
 * Providers report usage differently, so this model normalizes to one canonical shape:
 *
 *  - [inputTokens]      — fresh (non-cached) input tokens the model actually processed.
 *  - [outputTokens]     — completion tokens produced.
 *  - [cachedReadTokens] — input tokens served from a prompt cache (cheaper or free).
 *  - [cacheWriteTokens] — input tokens written to the prompt cache (usually billed at a
 *                         higher "cache write" rate, e.g. Anthropic `cache_creation_input_tokens`).
 *
 * [totalInputTokens] is the sum of all input categories; [totalTokens] adds output. These
 * categories map cleanly onto pricing that distinguishes fresh input, cached read and cache write.
 */
data class TokenUsage(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
) {
    val totalInputTokens: Long get() = inputTokens + cachedReadTokens + cacheWriteTokens
    val totalTokens: Long get() = totalInputTokens + outputTokens
    val isEmpty: Boolean get() = totalTokens == 0L
    val hasCacheData: Boolean get() = cachedReadTokens > 0L || cacheWriteTokens > 0L

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedReadTokens = cachedReadTokens + other.cachedReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
    )

    companion object {
        val ZERO = TokenUsage()
    }
}

/**
 * Reliability of a recorded token reading.
 *
 *  - [ACTUAL]      — metered by the provider (API `usage` object) or another authoritative source.
 *  - [ESTIMATED]   — computed from text with the conservative char/4 heuristic; never a meter reading.
 *  - [NOT_TRACKED] — no reliable usage data exists for the source (e.g. a CLI that does not expose it).
 *
 * The UI must keep these visually distinct and must never present an estimate as an actual reading.
 */
enum class Reliability(val label: String) {
    ACTUAL("actual"),
    ESTIMATED("estimated"),
    NOT_TRACKED("not tracked"),
}

/** Where a usage entry came from, so the dashboard can explain its provenance. */
enum class UsageSource(val label: String) {
    API("api"),
    CLI("cli"),
    SESSION_LOG("session log"),
    MANUAL("manual"),
}

/**
 * Parses the `usage` object from a provider response into a normalized [TokenUsage].
 *
 * The mapping is protocol-aware because the wire formats disagree on whether `prompt_tokens`
 * already includes cached tokens:
 *
 *  - OpenAI chat completions: `prompt_tokens` is the *total* input; the cached subset lives in
 *    `prompt_tokens_details.cached_tokens`. Fresh input = total − cached.
 *  - Anthropic Messages / gateways: `input_tokens` is *non-cached* input, with
 *    `cache_creation_input_tokens` (write) and `cache_read_input_tokens` (read) reported separately.
 *  - OpenRouter (OpenAI-shaped) also reports `prompt_tokens_details.cached_tokens`.
 *
 * A response with no usable usage data yields [TokenUsage.ZERO]; the caller then decides whether to
 * estimate or mark the value as unknown. Never fabricates numbers.
 */
object TokenUsageParser {
    fun parse(body: String, protocol: ProviderProtocol): TokenUsage {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return TokenUsage.ZERO
        val usage = root.optJSONObject("usage") ?: return TokenUsage.ZERO
        return parseUsageObject(usage, protocol)
    }

    /** Parses a standalone `usage` JSON object (as found in CLI result envelopes). */
    fun parseUsageObject(usage: JSONObject, protocol: ProviderProtocol): TokenUsage = when (protocol) {
        ProviderProtocol.OPENAI_CHAT,
        ProviderProtocol.OPENAI_RESPONSES,
        ProviderProtocol.OPENROUTER,
        -> parseOpenAiShape(usage)
        ProviderProtocol.ANTHROPIC,
        ProviderProtocol.ANTHROPIC_GATEWAY,
        ProviderProtocol.CLAUDE_LOGIN,
        -> parseAnthropicShape(usage)
    }

    /** OpenAI-compatible shape where `prompt_tokens` already includes the cached subset. */
    private fun parseOpenAiShape(usage: JSONObject): TokenUsage {
        val promptTokens = usage.optLong("prompt_tokens", -1L)
        val completionTokens = usage.optLong("completion_tokens", -1L)
        if (promptTokens < 0L && completionTokens < 0L) return TokenUsage.ZERO

        val cached = usage.optJSONObject("prompt_tokens_details")
            ?.optLong("cached_tokens", -1L)
            ?.takeIf { it > 0L }
            ?: 0L
        val freshInput = (promptTokens.coerceAtLeast(0L) - cached).coerceAtLeast(0L)
        return TokenUsage(
            inputTokens = freshInput,
            outputTokens = completionTokens.coerceAtLeast(0L),
            cachedReadTokens = cached,
            cacheWriteTokens = 0L,
        )
    }

    /** Anthropic shape where `input_tokens` is already non-cached input. */
    private fun parseAnthropicShape(usage: JSONObject): TokenUsage {
        val input = usage.optLong("input_tokens", -1L)
        val output = usage.optLong("output_tokens", -1L)
        if (input < 0L && output < 0L) return TokenUsage.ZERO
        return TokenUsage(
            inputTokens = input.coerceAtLeast(0L),
            outputTokens = output.coerceAtLeast(0L),
            cachedReadTokens = usage.optLong("cache_read_input_tokens", 0L).coerceAtLeast(0L),
            cacheWriteTokens = usage.optLong("cache_creation_input_tokens", 0L).coerceAtLeast(0L),
        )
    }
}

/**
 * Rough token estimate for text the provider did not meter. Uses the standard "4 characters ≈ 1
 * token" heuristic which is conservative and widely used. The result is always marked as an
 * estimate by the caller — this helper never claims real usage.
 */
object TokenUsageEstimator {
    const val CHARS_PER_TOKEN = 4

    /** Estimates tokens for a single piece of text (clamps negative to zero). */
    fun estimateTokens(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return (text.length / CHARS_PER_TOKEN).toLong().coerceAtLeast(1L)
    }

    /** Estimates a full request from prompt + completion text, zeroing the cache categories. */
    fun estimate(inputText: String?, outputText: String?): TokenUsage = TokenUsage(
        inputTokens = estimateTokens(inputText),
        outputTokens = estimateTokens(outputText),
    )
}
