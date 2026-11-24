package com.mobileharness.code.token

import java.util.Locale

/** The reason a cost component could not be computed, surfaced honestly in the UI. */
enum class CostAvailability { KNOWN, PARTIAL, UNAVAILABLE, ESTIMATED }

/**
 * A computed monetary cost for one request, decomposed by token category.
 *
 * Each category carries a nullable amount: null means "not billed / not priced", zero is a real
 * zero. [availability] is computed from the [TokenUsage] that produced this cost so the UI can
 * distinguish "known" from "partial" (some billed categories unpriced), "estimated" (tokens were
 * estimated, not metered) and "unavailable" (nothing priced).
 */
data class TokenCost(
    val input: Double? = null,
    val output: Double? = null,
    val cachedRead: Double? = null,
    val cacheWrite: Double? = null,
    val currency: String = "USD",
    val tokensEstimated: Boolean = false,
    val availability: CostAvailability = CostAvailability.UNAVAILABLE,
) {
    val total: Double? = listOfNotNull(input, output, cachedRead, cacheWrite).takeIf { it.isNotEmpty() }?.sum()

    fun availabilityLabel(): String = when (availability) {
        CostAvailability.KNOWN -> "known"
        CostAvailability.PARTIAL -> "partial"
        CostAvailability.UNAVAILABLE -> "price not available"
        CostAvailability.ESTIMATED -> "estimated"
    }
}

/**
 * Converts normalized [TokenUsage] into a [TokenCost] using [pricing]. Pure and deterministic.
 *
 * Effective price resolution order: user override → bundled reference → null (unknown). A null
 * price for a category that actually has tokens makes the result PARTIAL (or UNAVAILABLE if nothing
 * is priced) — never a fabricated number.
 */
object TokenCalculator {
    /** Resolves the effective pricing for a provider/model, preferring user overrides. */
    fun resolvePricing(providerId: String, model: String, overrides: Map<String, ModelPricing>): ModelPricing? {
        val key = TokenPricingCatalog.key(providerId, model)
        return overrides[key] ?: TokenPricingCatalog.reference(providerId, model)
    }

    fun cost(usage: TokenUsage, pricing: ModelPricing?, tokensEstimated: Boolean = false): TokenCost {
        if (pricing == null) {
            return TokenCost(
                currency = "USD",
                tokensEstimated = tokensEstimated,
                availability = if (tokensEstimated) CostAvailability.ESTIMATED else CostAvailability.UNAVAILABLE,
            )
        }
        val input = amountFor(usage.inputTokens, pricing.inputPerMillion)
        val output = amountFor(usage.outputTokens, pricing.outputPerMillion)
        val cachedRead = amountFor(usage.cachedReadTokens, pricing.cachedReadPerMillion)
        val cacheWrite = amountFor(usage.cacheWriteTokens, pricing.cacheWritePerMillion)

        val availability = when {
            tokensEstimated -> CostAvailability.ESTIMATED
            listOfNotNull(input, output, cachedRead, cacheWrite).isEmpty() -> CostAvailability.UNAVAILABLE
            allBilledCategoriesPriced(usage, input, output, cachedRead, cacheWrite) -> CostAvailability.KNOWN
            else -> CostAvailability.PARTIAL
        }

        return TokenCost(
            input = input,
            output = output,
            cachedRead = cachedRead,
            cacheWrite = cacheWrite,
            currency = pricing.currency,
            tokensEstimated = tokensEstimated,
            availability = availability,
        )
    }

    private fun amountFor(tokens: Long, pricePerMillion: Double?): Double? {
        if (tokens <= 0L || pricePerMillion == null) return null
        return tokens / 1_000_000.0 * pricePerMillion
    }

    private fun allBilledCategoriesPriced(
        usage: TokenUsage,
        input: Double?,
        output: Double?,
        cached: Double?,
        write: Double?,
    ): Boolean =
        (usage.inputTokens <= 0L || input != null) &&
            (usage.outputTokens <= 0L || output != null) &&
            (usage.cachedReadTokens <= 0L || cached != null) &&
            (usage.cacheWriteTokens <= 0L || write != null)

    /** Formats a cost in its currency; unknown totals render as the honest placeholder. */
    fun format(total: Double?, currency: String = "USD", unknownText: String = "price not available"): String {
        if (total == null) return unknownText
        val symbol = when (currency.uppercase(Locale.ROOT)) {
            "USD" -> "$"
            "EUR" -> "€"
            else -> "$currency "
        }
        return symbol + String.format(Locale.ROOT, "%.4f", total)
    }
}
