package com.mobileharness.code.token

import java.util.Locale
import org.json.JSONObject

/**
 * A model's context window. A null [maxTokens] means "unknown" — the app must show UNKNOWN rather
 * than inventing a number. Values in the bundled catalog are documented *references* (marked
 * [reference]) that the user (or a learned limit) can override.
 */
data class ContextWindow(val maxTokens: Long?) {
    val isKnown: Boolean get() = maxTokens != null && maxTokens > 0L
    companion object {
        val UNKNOWN = ContextWindow(null)
    }
}

/**
 * Live context occupancy for one model: how much is used vs how much the model can hold. All token
 * figures come from the normalized [TokenUsage] of a request; [maxTokens] resolves override →
 * learned → bundled reference → null.
 */
data class ContextUsage(
    val providerId: String,
    val model: String,
    val maxTokens: Long?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedReadTokens: Long,
    val cacheWriteTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + cachedReadTokens + cacheWriteTokens + outputTokens
    val isKnown: Boolean get() = maxTokens != null && maxTokens > 0L
    val remainingTokens: Long? get() = maxTokens?.takeIf { it > 0 }?.let { (it - totalTokens).coerceAtLeast(0L) }
    val percentUsed: Double? get() = maxTokens?.takeIf { it > 0 }?.let {
        (totalTokens.toDouble() / it * 100.0).coerceIn(0.0, 100.0)
    }
}

/**
 * Bundled reference context windows. Every value is a *documented* figure and is treated as a
 * reference only (the UI labels it "verify with provider"); it never overrides a user override or a
 * learned limit, and any model not listed here is UNKNOWN. These are standard (non-beta) windows.
 */
object ContextWindowCatalog {
    val referenceWindows: Map<String, Long> = mapOf(
        // OpenAI
        "openai/gpt-4o" to 128_000L,
        "openai/gpt-4o-mini" to 128_000L,
        "openai/gpt-4.1" to 1_047_576L,
        "openai/gpt-4.1-mini" to 1_047_576L,
        // Anthropic Claude (standard window; 1M is an opt-in beta and is not assumed here)
        "anthropic/claude-sonnet-4-5" to 200_000L,
        "anthropic/claude-sonnet-4-6" to 200_000L,
        "anthropic/claude-opus-4-6" to 200_000L,
        "anthropic/claude-haiku-4-6" to 200_000L,
    )

    fun key(providerId: String, model: String): String {
        val pid = providerId.trim().lowercase(Locale.ROOT).ifBlank { "custom" }
        val mid = model.trim().lowercase(Locale.ROOT)
        return "$pid/$mid"
    }

    fun reference(providerId: String, model: String): Long? = referenceWindows[key(providerId, model)]
}

/**
 * Pure context tracking and limit-learning.
 *
 * Resolution order for a model's window: user override → learned limit → bundled reference → null.
 * [ContextLimitLearner] only records a learned limit when a provider *explicitly* reports a maximum
 * (a strong, numeric pattern); it never infers a limit from an ambiguous error, and it never lowers
 * an existing override.
 */
object ContextUsageTracker {
    /** Resolves the effective max tokens for `providerId` + `model`. */
    fun resolveMaxTokens(
        providerId: String,
        model: String,
        overrides: Map<String, Long>,
        learned: Map<String, Long>,
    ): Long? {
        val key = ContextWindowCatalog.key(providerId, model)
        return overrides[key]?.takeIf { it > 0 }
            ?: learned[key]?.takeIf { it > 0 }
            ?: ContextWindowCatalog.reference(providerId, model)
    }

    /** Builds a [ContextUsage] from a [TokenUsage] and resolved window. */
    fun usageFor(
        providerId: String,
        model: String,
        usage: TokenUsage,
        overrides: Map<String, Long> = emptyMap(),
        learned: Map<String, Long> = emptyMap(),
    ): ContextUsage = ContextUsage(
        providerId = providerId,
        model = model,
        maxTokens = resolveMaxTokens(providerId, model, overrides, learned),
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        cachedReadTokens = usage.cachedReadTokens,
        cacheWriteTokens = usage.cacheWriteTokens,
    )
}

/** Evidence extracted from a provider error that a request exceeded the model's context window. */
data class OverflowEvidence(
    val observedMaxTokens: Long?,
    val confident: Boolean,
)

/**
 * Learns context limits only from reliable, explicit error messages. A single ambiguous error never
 * mutates the catalog; only a clear "maximum context length is N tokens" style message produces a
 * confident [OverflowEvidence].
 */
object ContextLimitLearner {
    private val MAX_PATTERNS = listOf(
        Regex("maximum context length is (\\d+)", RegexOption.IGNORE_CASE),
        Regex("max(?:imum)? context length of (\\d+)", RegexOption.IGNORE_CASE),
        Regex("context length (\\d+) exceeds maximum (\\d+)", RegexOption.IGNORE_CASE),
        Regex("context length is (\\d+), maximum is (\\d+)", RegexOption.IGNORE_CASE),
        Regex("requested (\\d+) tokens exceeds the maximum (\\d+)", RegexOption.IGNORE_CASE),
        Regex("prompt is too long: (\\d+) tokens, maximum (\\d+)", RegexOption.IGNORE_CASE),
        Regex("max context length is (\\d+)", RegexOption.IGNORE_CASE),
    )

    /** Parses an overflow error into [OverflowEvidence]; null when no reliable maximum is present. */
    fun parseOverflow(errorText: String): OverflowEvidence? {
        if (errorText.isBlank()) return null
        for (pattern in MAX_PATTERNS) {
            val match = pattern.find(errorText) ?: continue
            val groups = match.groupValues.drop(1)
            // Patterns with two groups put the *maximum* last; single-group patterns report the max.
            val candidate = groups.lastOrNull()?.toLongOrNull() ?: continue
            return OverflowEvidence(
                observedMaxTokens = candidate.takeIf { it >= MIN_PLAUSIBLE_TOKENS },
                confident = candidate >= MIN_PLAUSIBLE_TOKENS,
            )
        }
        return null
    }

    /** Whether a learned limit may replace an existing learned value (never a user override). */
    fun shouldUpdateLearned(evidence: OverflowEvidence, existingLearned: Long?): Boolean {
        if (!evidence.confident || evidence.observedMaxTokens == null) return false
        val observed = evidence.observedMaxTokens
        // Only lower an existing learned value with a *smaller* plausible max (providers can raise
        // limits over time; a single larger claim is not trusted to silently overwrite a smaller
        // one already learned, but a smaller, explicit maximum is a safe correction).
        return existingLearned == null || observed < existingLearned
    }

    private const val MIN_PLAUSIBLE_TOKENS = 1_024L
}

/** Pure JSON codec for a `providerId/model` → maxTokens map (overrides or learned limits). */
object ContextLimitCodec {
    fun toJson(map: Map<String, Long>): String {
        val root = JSONObject()
        map.forEach { (k, v) -> if (v > 0) root.put(k, v) }
        return root.toString()
    }

    fun fromJson(raw: String): Map<String, Long> = runCatching {
        val root = JSONObject(raw)
        val result = mutableMapOf<String, Long>()
        root.keys().forEach { key -> runCatching { result[key] = root.getLong(key) }.getOrNull() }
        result.filterValues { it > 0 }
    }.getOrDefault(emptyMap())
}
