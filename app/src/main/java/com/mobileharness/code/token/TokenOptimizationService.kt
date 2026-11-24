package com.mobileharness.code.token

import com.mobileharness.code.model.AiCapability
import com.mobileharness.code.model.AiProvider
import org.json.JSONObject

/**
 * Central, optional Token Optimization engine (Phase 6.2).
 *
 * This is a thin, independent layer that wraps the existing, already-tested token primitives
 * ([TokenContextCompactor], [TokenBudget]/[BudgetPolicy], [TokenOptimizer], [TokenSavings]) so the
 * Orchestrator can shape the context it hands between agents, gate steps behind a spend budget and
 * surface cheaper-model *suggestions* — without ever changing the Standard Mode agent launch paths,
 * the RuntimeBridge or the Sidekick engine.
 *
 * The whole service is default-OFF. When [TokenOptimizationServiceConfig.enabled] is false the
 * orchestrator behaves exactly as before (no compaction, no budget gate, no suggestions); each
 * individual optimization is additionally opt-in via its own sub-flag. It is pure JVM logic so it
 * is unit-testable without Android or a network.
 */

/** Master + per-feature switches for the central token optimization service. Default: everything OFF. */
data class TokenOptimizationServiceConfig(
    val enabled: Boolean = false,
    val contextShaping: Boolean = false,
    val budgetGating: Boolean = false,
    val modelAdvising: Boolean = false,
    val contextBudgetChars: Int = 8_000,
    val perTaskBudgetUsd: Double? = null,
) {
    companion object {
        val DEFAULT = TokenOptimizationServiceConfig()
        const val MIN_CONTEXT_BUDGET_CHARS = 500
        const val MAX_CONTEXT_BUDGET_CHARS = 100_000
    }
}

/**
 * The result of shaping the handoff context between two orchestrator steps. [applied] is true only
 * when content was actually removed; [removedChars] is a *measured* character reduction (never a
 * guessed token count), which the caller credits to the savings ledger.
 */
data class ShapeResult(
    val shapedResults: List<String>,
    val applied: Boolean,
    val originalChars: Int,
    val shapedChars: Int,
    val removedChars: Int,
    val summary: String? = null,
)

/**
 * Reduces the results of already-completed steps into a bounded handoff context. The goal and the
 * current task description are *not* part of this compaction (they are rendered separately by the
 * orchestrator), so they are always preserved verbatim. When the budget cannot safely accommodate
 * the content the original context is returned untouched ([applied] = false).
 */
object ContextShaper {
    fun shape(priorResults: List<String>, maxChars: Int): ShapeResult {
        val nonBlank = priorResults.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return ShapeResult(emptyList(), false, 0, 0, 0, null)
        val safeBudget = maxChars.coerceIn(
            TokenOptimizationServiceConfig.MIN_CONTEXT_BUDGET_CHARS,
            TokenOptimizationServiceConfig.MAX_CONTEXT_BUDGET_CHARS,
        )
        val blocks = nonBlank.map { ContextBlock(ContextPriority.RELEVANT_RESULTS, it) }
        val result = TokenContextCompactor.compactDetailed(blocks, safeBudget)
            ?: return ShapeResult(
                nonBlank, false,
                originalChars = nonBlank.sumOf { it.length },
                shapedChars = nonBlank.sumOf { it.length },
                removedChars = 0,
                summary = "Compaction aborted: content does not fit the budget; original context preserved.",
            )
        return ShapeResult(
            shapedResults = result.blocks.filter { it.text.isNotBlank() }.map { it.text },
            applied = result.removedChars > 0,
            originalChars = result.originalChars,
            shapedChars = result.compactedChars,
            removedChars = result.removedChars,
            summary = TokenContextCompactor.summarize(result),
        )
    }
}

/** Spend inputs for the orchestrator budget gate (derived from the real Token & Cost store). */
data class SpendSnapshot(
    val dailyUsd: Double = 0.0,
    val weeklyUsd: Double = 0.0,
    val monthlyUsd: Double = 0.0,
    val perAgentUsd: Map<String, Double> = emptyMap(),
    val taskUsd: Double = 0.0,
)

/** Outcome of a budget check for one orchestrator step. */
data class BudgetDecision(
    val status: BudgetStatus,
    val blocked: Boolean,
    val reason: String? = null,
    val taskSpendUsd: Double,
    val perTaskBudgetUsd: Double?,
    val perAgentSpendUsd: Double,
    val perAgentBudgetUsd: Double?,
)

/**
 * Gates an orchestrator step behind the existing [BudgetConfig] plus a per-task budget. It evaluates
 * the global daily/weekly/monthly windows, the per-agent limit (via [BudgetConfig.perAgentLimits])
 * and the optional per-task limit. Provider/model/project limits are intentionally NOT evaluated
 * here: the orchestrator layer does not know the provider/model/project of a CLI step, and those
 * budgets are already enforced at the Sidekick/CLI recording layer. An agent with no metered usage
 * (NOT_TRACKED) contributes $0 to its per-agent spend, so it never blocks by itself.
 */
object BudgetGate {
    fun check(
        snapshot: SpendSnapshot,
        budgetConfig: BudgetConfig,
        perTaskBudgetUsd: Double?,
        agentId: String?,
    ): BudgetDecision {
        val agentSpend = snapshot.perAgentUsd[agentId] ?: 0.0
        val agentLimit = agentId?.let { budgetConfig.perAgentLimits[it] }
        val status = BudgetPolicy.evaluate(
            config = budgetConfig,
            dailySpend = snapshot.dailyUsd,
            weeklySpend = snapshot.weeklyUsd,
            monthlySpend = snapshot.monthlyUsd,
            perProviderSpend = 0.0,
            perModelSpend = 0.0,
            perAgentSpend = agentSpend,
            perProjectSpend = 0.0,
            providerId = null,
            model = null,
            agent = agentId,
            projectId = null,
        )
        val taskStatus = if (perTaskBudgetUsd != null && perTaskBudgetUsd > 0.0) {
            BudgetPolicy.status(snapshot.taskUsd, perTaskBudgetUsd, budgetConfig.warnAtPercent)
        } else BudgetStatus.UNDER
        val combined = BudgetPolicy.combined(status, taskStatus)
        val blocked = BudgetPolicy.shouldBlock(combined, budgetConfig.reachedAction)
        val reason = when {
            blocked && combined == BudgetStatus.REACHED && taskStatus == BudgetStatus.REACHED ->
                "Per-task budget reached (spent $${snapshot.taskUsd} of $perTaskBudgetUsd). New steps are paused."
            blocked ->
                "Budget limit reached; new cost-bearing steps are paused. Adjust or reset your budget in Token & Cost Manager."
            combined == BudgetStatus.WARNING -> "Budget warning: approaching a configured limit."
            else -> null
        }
        return BudgetDecision(
            status = combined,
            blocked = blocked,
            reason = reason,
            taskSpendUsd = snapshot.taskUsd,
            perTaskBudgetUsd = perTaskBudgetUsd,
            perAgentSpendUsd = agentSpend,
            perAgentBudgetUsd = agentLimit,
        )
    }
}

/** A cheaper-model recommendation for a specific task (suggestion only — never auto-applied). */
data class TaskModelAdvice(
    val taskTitle: String,
    val lightTask: Boolean,
    val suggestion: ModelSuggestion? = null,
)

/**
 * Proposes a cheaper model only for *light* auxiliary tasks (summarize/classify/format/short
 * research, etc.). Complex coding or critical steps are never recommended a weaker model. It is
 * fail-closed: an unrecognized task is treated as not-light, and [TokenOptimizer.suggestCheaperModel]
 * already refuses any candidate it cannot verify as cheaper + capability-compatible.
 */
object ModelAdvisor {
    private val LIGHT_KEYWORDS = listOf(
        "summar", "classif", "format", "sort", "rename", "extract", "translate",
        "annotate", "tag", "label", "parse", "quick", "short", "research",
    )

    fun isLightTask(title: String, description: String): Boolean {
        val text = "$title $description".lowercase()
        return LIGHT_KEYWORDS.any { text.contains(it) }
    }

    fun advise(
        taskTitle: String,
        taskDescription: String,
        provider: AiProvider,
        currentModel: String,
        requiredCapabilities: Set<AiCapability> = setOf(AiCapability.CHAT_COMPLETIONS),
        cheaperCandidates: List<String>,
        pricingFor: (String) -> ModelPricing?,
        enabled: Boolean,
    ): TaskModelAdvice {
        if (!enabled) return TaskModelAdvice(taskTitle, false, null)
        val light = isLightTask(taskTitle, taskDescription)
        if (!light) return TaskModelAdvice(taskTitle, false, null)
        return TaskModelAdvice(
            taskTitle = taskTitle,
            lightTask = true,
            suggestion = TokenOptimizer.suggestCheaperModel(
                provider, currentModel, requiredCapabilities, cheaperCandidates, pricingFor,
            ),
        )
    }
}

/** Immutable snapshot of the savings ledger, for the UI. */
data class SavingsLedgerSnapshot(
    val compactionEvents: Int = 0,
    val compactionTokensEstimated: Long = 0L,
    val modelChoiceEvents: Int = 0,
    val modelChoiceTokensEstimated: Long = 0L,
)

/**
 * Accumulates the savings the service actually produces during a run. Compaction savings are
 * measured in characters and converted to an *estimated* token count (char/4); model-choice savings
 * must be supplied by the caller. All values are explicitly "estimated" — never presented as metered.
 */
class SavingsLedger {
    private var compactionEvents = 0
    private var compactionTokensEstimated = 0L
    private var modelChoiceEvents = 0
    private var modelChoiceTokensEstimated = 0L

    /** Credits a measured character reduction as an estimated token saving. */
    fun recordCompaction(removedChars: Int) {
        if (removedChars <= 0) return
        val tokens = (removedChars / TokenUsageEstimator.CHARS_PER_TOKEN).toLong().coerceAtLeast(1L)
        compactionTokensEstimated += tokens
        compactionEvents++
    }

    /** Credits a deliberate cheaper-model choice (tokens saved must come from a real basis). */
    fun recordModelChoice(tokensSaved: Long) {
        val value = tokensSaved.coerceAtLeast(0L)
        if (value > 0L) {
            modelChoiceTokensEstimated += value
            modelChoiceEvents++
        }
    }

    fun snapshot(): SavingsLedgerSnapshot =
        SavingsLedgerSnapshot(compactionEvents, compactionTokensEstimated, modelChoiceEvents, modelChoiceTokensEstimated)

    /** Returns and resets the accumulated (compactionTokens, modelChoiceTokens) estimates. */
    fun drain(): Pair<Long, Long> {
        val out = compactionTokensEstimated to modelChoiceTokensEstimated
        compactionEvents = 0
        compactionTokensEstimated = 0L
        modelChoiceEvents = 0
        modelChoiceTokensEstimated = 0L
        return out
    }
}

/**
 * The composed service. It is constructed per orchestrator run with the current user config and a
 * fresh ledger; the Android layer supplies the spend snapshot (from the Token & Cost store) and the
 * budget config (from preferences). When [config.enabled] is false every method is a no-op that
 * preserves the original context and never blocks or suggests anything.
 */
class TokenOptimizationService(
    val config: TokenOptimizationServiceConfig = TokenOptimizationServiceConfig.DEFAULT,
    val ledger: SavingsLedger = SavingsLedger(),
) {
    val enabled: Boolean get() = config.enabled

    /** Shapes the handoff context (or returns it unchanged when shaping is off/fails). */
    fun shapeHandoff(priorResults: List<String>): ShapeResult {
        if (!enabled || !config.contextShaping) {
            val nonBlank = priorResults.map { it.trim() }.filter { it.isNotEmpty() }
            return ShapeResult(nonBlank, false, 0, 0, 0, null)
        }
        val result = ContextShaper.shape(priorResults, config.contextBudgetChars)
        ledger.recordCompaction(result.removedChars)
        return result
    }

    /** Checks the budget (no-op decision when budget gating is off). */
    fun checkBudget(snapshot: SpendSnapshot, budgetConfig: BudgetConfig, agentId: String?): BudgetDecision {
        if (!enabled || !config.budgetGating) {
            return BudgetDecision(
                status = BudgetStatus.UNDER,
                blocked = false,
                taskSpendUsd = snapshot.taskUsd,
                perTaskBudgetUsd = config.perTaskBudgetUsd,
                perAgentSpendUsd = snapshot.perAgentUsd[agentId] ?: 0.0,
                perAgentBudgetUsd = agentId?.let { budgetConfig.perAgentLimits[it] },
            )
        }
        return BudgetGate.check(snapshot, budgetConfig, config.perTaskBudgetUsd, agentId)
    }

    /** Produces a cheaper-model suggestion for a light task (or none when advising is off). */
    fun adviseModel(
        taskTitle: String,
        taskDescription: String,
        provider: AiProvider,
        currentModel: String,
        requiredCapabilities: Set<AiCapability> = setOf(AiCapability.CHAT_COMPLETIONS),
        cheaperCandidates: List<String>,
        pricingFor: (String) -> ModelPricing?,
    ): TaskModelAdvice = ModelAdvisor.advise(
        taskTitle, taskDescription, provider, currentModel, requiredCapabilities, cheaperCandidates,
        pricingFor, enabled && config.modelAdvising,
    )
}

/** Pure JSON codec for [TokenOptimizationServiceConfig]; independent of Android so it is unit-testable. */
object TokenOptimizationServiceCodec {
    fun toJson(config: TokenOptimizationServiceConfig): String = JSONObject()
        .put("enabled", config.enabled)
        .put("contextShaping", config.contextShaping)
        .put("budgetGating", config.budgetGating)
        .put("modelAdvising", config.modelAdvising)
        .put("contextBudgetChars", config.contextBudgetChars)
        .put("perTaskBudgetUsd", config.perTaskBudgetUsd ?: JSONObject.NULL)
        .toString()

    fun fromJson(raw: String): TokenOptimizationServiceConfig = runCatching {
        val root = JSONObject(raw)
        TokenOptimizationServiceConfig(
            enabled = root.optBoolean("enabled", false),
            contextShaping = root.optBoolean("contextShaping", false),
            budgetGating = root.optBoolean("budgetGating", false),
            modelAdvising = root.optBoolean("modelAdvising", false),
            contextBudgetChars = root.optInt("contextBudgetChars", 8_000)
                .coerceIn(TokenOptimizationServiceConfig.MIN_CONTEXT_BUDGET_CHARS, TokenOptimizationServiceConfig.MAX_CONTEXT_BUDGET_CHARS),
            perTaskBudgetUsd = if (root.has("perTaskBudgetUsd") && root.opt("perTaskBudgetUsd") != JSONObject.NULL) {
                root.optDouble("perTaskBudgetUsd").takeIf { it > 0.0 }
            } else null,
        )
    }.getOrDefault(TokenOptimizationServiceConfig.DEFAULT)
}
