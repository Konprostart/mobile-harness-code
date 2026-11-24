package com.mobileharness.code.token

import java.util.Locale
import org.json.JSONObject

/**
 * Price per one million tokens for a single model. All amounts are in [currency] (USD by default).
 * A null field means "this price is unknown" — the cost calculator must report
 * "price not available" for that component rather than inventing a number.
 */
data class ModelPricing(
    val inputPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
    val cachedReadPerMillion: Double? = null,
    val cacheWritePerMillion: Double? = null,
    val currency: String = "USD",
    /** True when this is a bundled reference value that must be verified against the provider. */
    val reference: Boolean = false,
) {
    val isKnown: Boolean
        get() = inputPerMillion != null || outputPerMillion != null ||
            cachedReadPerMillion != null || cacheWritePerMillion != null
}

/**
 * Bundled, clearly-marked reference prices. These are the *only* prices the app ships; every one
 * carries [ModelPricing.reference] = true so the UI labels it "reference — verify with provider",
 * and the user can override any value from the Token & Cost Manager screen. Prices change over
 * time and are never treated as authoritative — the calculator prefers a user override first, then
 * these references, then reports "not available".
 */
object TokenPricingCatalog {
    val referencePrices: Map<String, ModelPricing> = mapOf(
        // OpenAI (USD per 1M tokens, input / output). Verified references only.
        "openai/gpt-4o" to ModelPricing(inputPerMillion = 2.50, outputPerMillion = 10.00, reference = true),
        "openai/gpt-4o-mini" to ModelPricing(inputPerMillion = 0.15, outputPerMillion = 0.60, reference = true),
        // Anthropic Claude family (USD per 1M). Cached read/write priced separately.
        "anthropic/claude-sonnet-4-5" to ModelPricing(inputPerMillion = 3.00, outputPerMillion = 15.00, cachedReadPerMillion = 0.30, cacheWritePerMillion = 3.75, reference = true),
        "anthropic/claude-haiku-4-6" to ModelPricing(inputPerMillion = 0.80, outputPerMillion = 4.00, cachedReadPerMillion = 0.08, cacheWritePerMillion = 1.00, reference = true),
        // DeepSeek (USD per 1M, input cache-miss / output). Reference only.
        "deepseek/deepseek-chat" to ModelPricing(inputPerMillion = 0.27, outputPerMillion = 1.10, reference = true),
    )

    /** Normalized lookup key: providerId/model, lowercase. */
    fun key(providerId: String, model: String): String {
        val pid = providerId.trim().lowercase(Locale.ROOT).ifBlank { "custom" }
        val mid = model.trim().lowercase(Locale.ROOT)
        return "$pid/$mid"
    }

    /** Bundled reference price for `providerId` + `model`, or null when unknown. */
    fun reference(providerId: String, model: String): ModelPricing? =
        referencePrices[key(providerId, model)]
}

/**
 * Pure JSON codec for per-model pricing overrides (user corrections). Keys are the normalized
 * `providerId/model` pair; values are [ModelPricing]. No Android dependency so it is unit-testable.
 */
object PricingOverrideCodec {
    fun toJson(overrides: Map<String, ModelPricing>): String {
        val root = JSONObject()
        overrides.forEach { (key, pricing) ->
            root.put(
                key,
                JSONObject()
                    .put("currency", pricing.currency)
                    .put("input", pricing.inputPerMillion ?: JSONObject.NULL)
                    .put("output", pricing.outputPerMillion ?: JSONObject.NULL)
                    .put("cachedRead", pricing.cachedReadPerMillion ?: JSONObject.NULL)
                    .put("cacheWrite", pricing.cacheWritePerMillion ?: JSONObject.NULL),
            )
        }
        return root.toString()
    }

    fun fromJson(raw: String): Map<String, ModelPricing> = runCatching {
        val root = JSONObject(raw)
        val result = mutableMapOf<String, ModelPricing>()
        root.keys().forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            val pricing = ModelPricing(
                inputPerMillion = obj.nullableDouble("input"),
                outputPerMillion = obj.nullableDouble("output"),
                cachedReadPerMillion = obj.nullableDouble("cachedRead"),
                cacheWritePerMillion = obj.nullableDouble("cacheWrite"),
                currency = obj.optString("currency").ifBlank { "USD" },
                reference = false,
            )
            if (pricing.isKnown) result[key] = pricing
        }
        result
    }.getOrDefault(emptyMap())

    private fun JSONObject.nullableDouble(name: String): Double? =
        if (has(name) && opt(name) != JSONObject.NULL) optDouble(name) else null
}
