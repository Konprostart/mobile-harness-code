package com.mobileharness.code.token

/**
 * Aggregate token/cost statistics over a set of usage entries. All totals are simple sums; where a
 * cost cannot be computed for every entry, [costKnown] is false and [totalCost] is the sum of the
 * entries that *were* priced (partial) — never fabricated.
 */
data class TokenTotals(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val requests: Long = 0L,
    val totalCost: Double? = null,
    val costKnown: Boolean = false,
    val estimatedEntries: Int = 0,
    val entries: Int = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cachedReadTokens + cacheWriteTokens
    val totalInputTokens: Long get() = inputTokens + cachedReadTokens + cacheWriteTokens
}

/**
 * Savings attribution. Each bucket holds the token count the optimizer credits to a specific
 * mechanism, plus the estimated monetary value when a price is available. Values are always derived
 * from a real basis (cache read tokens, compaction reduction, cheaper-model delta) — never guessed.
 */
data class SavingsReport(
    val tokensUsed: Long = 0L,
    val tokensSavedByCache: Long = 0L,
    val tokensSavedByCompaction: Long = 0L,
    val tokensSavedByModelChoice: Long = 0L,
    val estimatedCostSaved: Double? = null,
) {
    val totalTokensSaved: Long get() = tokensSavedByCache + tokensSavedByCompaction + tokensSavedByModelChoice
    val savingsBasis: Boolean get() = totalTokensSaved > 0L || estimatedCostSaved != null
}

/**
 * Aggregates usage entries into [TokenTotals] and derives a conservative [SavingsReport].
 *
 * Honesty rules:
 *  - Cache savings = `cachedReadTokens` (tokens served from cache instead of being billed at the
 *    fresh-input rate) — a real, metered number.
 *  - Compaction savings must be supplied by the caller (the difference between pre- and post-
 *    compaction token counts); it is never inferred from usage alone.
 *  - Model-choice savings must also be supplied by the caller (the delta of a deliberate switch).
 */
object TokenSavings {
    fun totals(
        entries: List<TokenUsageEntry>,
        pricingFor: (providerId: String, model: String) -> ModelPricing?,
    ): TokenTotals {
        var input = 0L
        var output = 0L
        var cachedRead = 0L
        var cacheWrite = 0L
        var requests = 0L
        var estimatedEntries = 0
        var costSum = 0.0
        var anyCost = false
        var allPriced = true

        entries.forEach { entry ->
            input += entry.usage.inputTokens
            output += entry.usage.outputTokens
            cachedRead += entry.usage.cachedReadTokens
            cacheWrite += entry.usage.cacheWriteTokens
            requests += entry.requestCount
            if (entry.tokensEstimated) estimatedEntries++

            val pricing = pricingFor(entry.providerId, entry.model)
            val cost = TokenCalculator.cost(entry.usage, pricing, entry.tokensEstimated)
            if (cost.total != null) {
                anyCost = true
                costSum += cost.total
            } else if (entry.usage.totalTokens > 0L) {
                allPriced = false
            }
        }

        return TokenTotals(
            inputTokens = input,
            outputTokens = output,
            cachedReadTokens = cachedRead,
            cacheWriteTokens = cacheWrite,
            requests = requests,
            totalCost = if (anyCost) costSum else null,
            costKnown = anyCost && allPriced,
            estimatedEntries = estimatedEntries,
            entries = entries.size,
        )
    }

    fun savingsReport(
        totals: TokenTotals,
        compactionTokensSaved: Long = 0L,
        modelChoiceTokensSaved: Long = 0L,
        costSavedByModelChoice: Double? = null,
    ): SavingsReport = SavingsReport(
        tokensUsed = totals.totalTokens,
        tokensSavedByCache = totals.cachedReadTokens,
        tokensSavedByCompaction = compactionTokensSaved.coerceAtLeast(0L),
        tokensSavedByModelChoice = modelChoiceTokensSaved.coerceAtLeast(0L),
        estimatedCostSaved = costSavedByModelChoice,
    )
}
