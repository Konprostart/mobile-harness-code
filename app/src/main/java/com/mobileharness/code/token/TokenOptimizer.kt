package com.mobileharness.code.token

import com.mobileharness.code.model.AiCapability
import com.mobileharness.code.model.AiCompatibility
import com.mobileharness.code.model.AiProvider
import org.json.JSONObject

/** How aggressively the token optimizer may act. Never changes the user's explicit choices by default. */
enum class OptimizationLevel(val label: String) {
    LIGHT("Light saving"),
    BALANCED("Balanced saving"),
    MAXIMUM("Maximum saving"),
}

/**
 * The user's full optimization configuration. Every aggressive behavior is opt-in and off by
 * default: auto-compaction, smart model selection and prompt caching are disabled until the user
 * turns them on. [enabled] is the master AUTO TOKEN OPTIMIZATION switch; when false the optimizer
 * never changes agent behavior (usage tracking stays active).
 */
data class OptimizationConfig(
    val enabled: Boolean = false,
    val level: OptimizationLevel = OptimizationLevel.LIGHT,
    val autoCompact: Boolean = false,
    val promptCaching: Boolean = false,
    val smartModelSelection: Boolean = false,
    val maxCostPerMillion: Double? = null,
    val minQualityTier: QualityTier = QualityTier.ANY,
) {
    companion object {
        val DEFAULT = OptimizationConfig()
    }
}

/** Coarse quality tier used by smart model selection; models without a known tier are [UNKNOWN]. */
enum class QualityTier(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    ANY("Any"),
    UNKNOWN("Unknown"),
}

/**
 * A cheaper-model suggestion. [reasons] explains exactly why the suggestion is safe (or why it was
 * refused) so the UI never presents a model as interchangeable when it is not.
 */
data class ModelSuggestion(
    val currentModel: String,
    val suggestedModel: String?,
    val reasons: List<String>,
) {
    val isActionable: Boolean get() = suggestedModel != null
}

/**
 * Pure optimization policy shared by every AI tool.
 *
 * It decides, deterministically, which behaviors a given [OptimizationConfig] permits and — for
 * smart model selection — whether a cheaper candidate is genuinely compatible before suggesting it.
 * It never changes a model outright: it returns a *suggestion* the user (or an explicit opt-in) acts
 * on, and it always checks capabilities, protocol and context fitness first.
 */
object TokenOptimizer {
    /** Whether prompt caching may be requested for this provider under the current config. */
    fun mayCache(config: OptimizationConfig, provider: AiProvider, model: String): Boolean {
        if (!config.enabled || !config.promptCaching) return false
        return providerSupportsCaching(provider, model)
    }

    /** Whether caching is meaningful for this provider+model (Anthropic-family or OpenAI cached-read). */
    fun providerSupportsCaching(provider: AiProvider, model: String): Boolean =
        AiCompatibility.supports(provider, model, AiCapability.STREAMING) && (
            provider.id == "anthropic" ||
                provider.apiFormats.any { it.name == "ANTHROPIC_MESSAGES" } ||
                provider.id in CACHING_PROVIDER_IDS
            )

    /** Whether auto-compaction is permitted at the configured level. */
    fun mayCompact(config: OptimizationConfig): Boolean =
        config.enabled && config.autoCompact && config.level != OptimizationLevel.LIGHT

    /** Whether the optimizer may propose a cheaper model (never auto-applied). */
    fun maySuggestModel(config: OptimizationConfig): Boolean =
        config.enabled && config.smartModelSelection

    /**
     * Produces a cheaper-model suggestion for `currentModel` at [provider]. The candidate must be
     * different, cheaper, and still support every [requiredCapability]; otherwise the suggestion is
     * refused with a clear reason. This is fail-closed: it never returns a model it cannot verify.
     */
    fun suggestCheaperModel(
        provider: AiProvider,
        currentModel: String,
        requiredCapabilities: Set<AiCapability> = setOf(AiCapability.CHAT_COMPLETIONS),
        cheaperCandidates: List<String>,
        pricingFor: (String) -> ModelPricing?,
    ): ModelSuggestion {
        if (currentModel.isBlank()) {
            return ModelSuggestion(currentModel, null, listOf("no current model selected"))
        }
        val currentPrice = pricingFor(currentModel)?.outputPerMillion
            ?: pricingFor(currentModel)?.inputPerMillion

        val candidate = cheaperCandidates
            .filter { it != currentModel }
            .filter { model -> requiredCapabilities.all { AiCompatibility.supports(provider, model, it) } }
            .firstOrNull { model ->
                val price = pricingFor(model)?.outputPerMillion ?: pricingFor(model)?.inputPerMillion
                price != null && (currentPrice == null || price < currentPrice)
            }

        if (candidate == null) {
            return ModelSuggestion(
                currentModel,
                null,
                listOf(
                    "no cheaper compatible model found" +
                        (if (currentPrice == null) " (current model price unknown)" else ""),
                ),
            )
        }
        return ModelSuggestion(
            currentModel,
            candidate,
            listOf(
                "cheaper than the current model",
                "supports all required capabilities: ${requiredCapabilities.joinToString(", ") { it.label }}",
            ),
        )
    }

    /** Provider ids known to support prompt caching (kept explicit, never assumed). */
    private val CACHING_PROVIDER_IDS = setOf("openai", "openrouter", "deepseek", "anthropic")
}

/** Pure JSON codec for [OptimizationConfig]; independent of Android so it is unit-testable. */
object OptimizationConfigCodec {
    fun toJson(config: OptimizationConfig): String = JSONObject()
        .put("enabled", config.enabled)
        .put("level", config.level.name)
        .put("autoCompact", config.autoCompact)
        .put("promptCaching", config.promptCaching)
        .put("smartModelSelection", config.smartModelSelection)
        .put("maxCostPerMillion", config.maxCostPerMillion ?: JSONObject.NULL)
        .put("minQualityTier", config.minQualityTier.name)
        .toString()

    fun fromJson(raw: String): OptimizationConfig = runCatching {
        val root = JSONObject(raw)
        OptimizationConfig(
            enabled = root.optBoolean("enabled", false),
            level = OptimizationLevel.entries.firstOrNull { it.name == root.optString("level") }
                ?: OptimizationLevel.LIGHT,
            autoCompact = root.optBoolean("autoCompact", false),
            promptCaching = root.optBoolean("promptCaching", false),
            smartModelSelection = root.optBoolean("smartModelSelection", false),
            maxCostPerMillion = if (root.has("maxCostPerMillion") && root.opt("maxCostPerMillion") != JSONObject.NULL) {
                root.optDouble("maxCostPerMillion")
            } else null,
            minQualityTier = QualityTier.entries.firstOrNull { it.name == root.optString("minQualityTier") }
                ?: QualityTier.ANY,
        )
    }.getOrDefault(OptimizationConfig.DEFAULT)
}
