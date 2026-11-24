package com.mobileharness.code.token

import org.json.JSONObject

/** What the app should do once a budget limit is reached. */
enum class BudgetReachedAction(val storageKey: String) {
    WARN_ONLY("warn"),
    SUGGEST_CHEAPER("suggest"),
    INCREASE_OPTIMIZATION("optimize"),
    STOP_NEW_REQUESTS("stop"),
}

/**
 * Local, user-controlled spend budgets (independent of any provider-side limit). All amounts are in
 * the pricing currency (USD by default). A null amount means "no limit set for that window".
 */
data class BudgetConfig(
    val dailyLimit: Double? = null,
    val weeklyLimit: Double? = null,
    val monthlyLimit: Double? = null,
    val perProviderLimits: Map<String, Double> = emptyMap(),
    val perModelLimits: Map<String, Double> = emptyMap(),
    val perAgentLimits: Map<String, Double> = emptyMap(),
    val perProjectLimits: Map<String, Double> = emptyMap(),
    val warnAtPercent: Int = 80,
    val reachedAction: BudgetReachedAction = BudgetReachedAction.WARN_ONLY,
) {
    companion object {
        val DEFAULT = BudgetConfig()
        const val MAX_PERCENT = 100
    }
}

/** Outcome of checking current spend against a single budget window. */
enum class BudgetStatus { UNDER, WARNING, REACHED }

/**
 * Pure, deterministic budget policy. It compares a running spend total against the configured
 * limits and derives a [BudgetStatus] plus whether new cost-bearing requests should be blocked
 * (only when the user explicitly chose STOP_NEW_REQUESTS and a limit is actually reached).
 */
object BudgetPolicy {
    fun status(spend: Double, limit: Double?, warnAtPercent: Int): BudgetStatus {
        if (limit == null || limit <= 0.0) return BudgetStatus.UNDER
        if (spend >= limit) return BudgetStatus.REACHED
        val warnAt = limit * (warnAtPercent.coerceIn(1, 100) / 100.0)
        return if (spend >= warnAt) BudgetStatus.WARNING else BudgetStatus.UNDER
    }

    /** The most severe status across several limits, for a single provider/model/global check. */
    fun combined(vararg statuses: BudgetStatus): BudgetStatus =
        when {
            statuses.any { it == BudgetStatus.REACHED } -> BudgetStatus.REACHED
            statuses.any { it == BudgetStatus.WARNING } -> BudgetStatus.WARNING
            else -> BudgetStatus.UNDER
        }

    /**
     * Evaluates every budget window (global, per-provider, per-model, per-agent, per-project) at
     * once. [dailySpend]/[weeklySpend]/[monthlySpend] are the rolling-window totals; the per-*
     * spends are cumulative over the whole store. Missing limits simply contribute UNDER.
     */
    fun evaluate(
        config: BudgetConfig,
        dailySpend: Double,
        weeklySpend: Double,
        monthlySpend: Double,
        perProviderSpend: Double,
        perModelSpend: Double,
        perAgentSpend: Double = 0.0,
        perProjectSpend: Double = 0.0,
        providerId: String? = null,
        model: String? = null,
        agent: String? = null,
        projectId: String? = null,
    ): BudgetStatus = combined(
        status(dailySpend, config.dailyLimit, config.warnAtPercent),
        status(weeklySpend, config.weeklyLimit, config.warnAtPercent),
        status(monthlySpend, config.monthlyLimit, config.warnAtPercent),
        status(perProviderSpend, providerId?.let { config.perProviderLimits[it] }, config.warnAtPercent),
        status(perModelSpend, model?.let { config.perModelLimits[it] }, config.warnAtPercent),
        status(perAgentSpend, agent?.let { config.perAgentLimits[it] }, config.warnAtPercent),
        status(perProjectSpend, projectId?.let { config.perProjectLimits[it] }, config.warnAtPercent),
    )

    /**
     * True when the app must refuse new cost-bearing requests. This is fail-safe: it is only ever
     * true when the user explicitly selected STOP_NEW_REQUESTS *and* a limit is actually reached.
     */
    fun shouldBlock(status: BudgetStatus, action: BudgetReachedAction): Boolean =
        status == BudgetStatus.REACHED && action == BudgetReachedAction.STOP_NEW_REQUESTS

    /**
     * True when a budget limit is reached. It never blocks on its own: the action decides behavior.
     */
    fun isReached(status: BudgetStatus): Boolean = status == BudgetStatus.REACHED
}

/** Pure JSON codec for [BudgetConfig]; independent of Android so it is unit-testable. */
object BudgetConfigCodec {
    fun toJson(config: BudgetConfig): String {
        val perProvider = JSONObject()
        config.perProviderLimits.forEach { (k, v) -> perProvider.put(k, v) }
        val perModel = JSONObject()
        config.perModelLimits.forEach { (k, v) -> perModel.put(k, v) }
        val perAgent = JSONObject()
        config.perAgentLimits.forEach { (k, v) -> perAgent.put(k, v) }
        val perProject = JSONObject()
        config.perProjectLimits.forEach { (k, v) -> perProject.put(k, v) }
        return JSONObject()
            .put("daily", config.dailyLimit ?: JSONObject.NULL)
            .put("weekly", config.weeklyLimit ?: JSONObject.NULL)
            .put("monthly", config.monthlyLimit ?: JSONObject.NULL)
            .put("perProvider", perProvider)
            .put("perModel", perModel)
            .put("perAgent", perAgent)
            .put("perProject", perProject)
            .put("warnAtPercent", config.warnAtPercent)
            .put("reachedAction", config.reachedAction.storageKey)
            .toString()
    }

    fun fromJson(raw: String): BudgetConfig = runCatching {
        val root = JSONObject(raw)
        BudgetConfig(
            dailyLimit = root.nullableDouble("daily"),
            weeklyLimit = root.nullableDouble("weekly"),
            monthlyLimit = root.nullableDouble("monthly"),
            perProviderLimits = root.optJSONObject("perProvider")?.toDoubleMap() ?: emptyMap(),
            perModelLimits = root.optJSONObject("perModel")?.toDoubleMap() ?: emptyMap(),
            perAgentLimits = root.optJSONObject("perAgent")?.toDoubleMap() ?: emptyMap(),
            perProjectLimits = root.optJSONObject("perProject")?.toDoubleMap() ?: emptyMap(),
            warnAtPercent = root.optInt("warnAtPercent", 80).coerceIn(1, 100),
            reachedAction = BudgetReachedAction.entries.firstOrNull {
                it.storageKey == root.optString("reachedAction")
            } ?: BudgetReachedAction.WARN_ONLY,
        )
    }.getOrDefault(BudgetConfig.DEFAULT)

    private fun JSONObject.nullableDouble(name: String): Double? =
        if (has(name) && opt(name) != JSONObject.NULL) optDouble(name) else null

    private fun JSONObject.toDoubleMap(): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        keys().forEach { key -> runCatching { result[key] = getDouble(key) } }
        return result
    }
}
