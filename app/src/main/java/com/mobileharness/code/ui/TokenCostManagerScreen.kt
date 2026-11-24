package com.mobileharness.code.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.token.BudgetConfig
import com.mobileharness.code.token.BudgetPolicy
import com.mobileharness.code.token.BudgetReachedAction
import com.mobileharness.code.token.BudgetStatus
import com.mobileharness.code.token.ContextUsageTracker
import com.mobileharness.code.token.ContextWindowCatalog
import com.mobileharness.code.token.ModelPricing
import com.mobileharness.code.token.OptimizationConfig
import com.mobileharness.code.token.OptimizationLevel
import com.mobileharness.code.token.QualityTier
import com.mobileharness.code.token.Reliability
import com.mobileharness.code.token.SavingsReport
import com.mobileharness.code.token.TokenCalculator
import com.mobileharness.code.token.TokenPricingCatalog
import com.mobileharness.code.token.TokenSavings
import com.mobileharness.code.token.TokenTotals
import com.mobileharness.code.token.TokenUsageEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Formatting helpers -------------------------------------------------------------------

private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000_000L -> String.format(Locale.ROOT, "%.2fB", n / 1_000_000_000.0)
    n >= 1_000_000L -> String.format(Locale.ROOT, "%.2fM", n / 1_000_000.0)
    n >= 1_000L -> String.format(Locale.ROOT, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun fmtDate(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))

private fun fmtCost(total: Double?, currency: String = "USD"): String =
    TokenCalculator.format(total, currency)

/** One breakdown row (agent/provider/model) with its token + cost totals. */
private data class BreakdownRow(val key: String, val totals: TokenTotals)

private fun breakdown(
    entries: List<TokenUsageEntry>,
    overrides: Map<String, ModelPricing>,
    keyOf: (TokenUsageEntry) -> String,
): List<BreakdownRow> {
    val pricingFor: (String, String) -> ModelPricing? = { pid, m -> TokenCalculator.resolvePricing(pid, m, overrides) }
    return entries.groupBy(keyOf)
        .map { (key, group) -> BreakdownRow(key, TokenSavings.totals(group, pricingFor)) }
        .sortedByDescending { it.totals.totalTokens }
}

/** Filters entries to a rolling window in milliseconds (0 = all). */
private fun windowed(entries: List<TokenUsageEntry>, windowMillis: Long): List<TokenUsageEntry> {
    if (windowMillis <= 0L) return entries
    val cutoff = System.currentTimeMillis() - windowMillis
    return entries.filter { it.timestampMillis >= cutoff }
}

private fun distinct(values: List<String>): List<String> = values.filter { it.isNotBlank() }.distinct().sorted()

private fun reliabilityCount(entries: List<TokenUsageEntry>, reliability: Reliability): Int =
    entries.count { it.reliability == reliability }

@Composable
private fun ReliabilityFilter(selected: Reliability?, onSelect: (Reliability?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reliability", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
        Reliability.entries.forEach { r ->
            FilterChip(
                selected = selected == r,
                onClick = { onSelect(if (selected == r) null else r) },
                label = { Text(r.label) },
            )
        }
    }
}

// --- Main screen --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenCostManagerScreen(
    state: AppUiState,
    onClose: () -> Unit,
    onSetOptimizationConfig: (OptimizationConfig) -> Unit,
    onSetBudget: (BudgetConfig) -> Unit,
    onSetPricingOverrides: (Map<String, ModelPricing>) -> Unit,
    onClearUsage: () -> Unit,
    onResetSavings: () -> Unit,
    onReload: () -> Unit,
    onSetContextWindowOverride: (String, String, Long) -> Unit = { _, _, _ -> },
    onResetContextWindowOverrides: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onReload() }

    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Optimization", "Budgets", "Pricing", "Context", "Savings")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Token & Cost Manager", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            when (tab) {
                0 -> OverviewTab(state, onClearUsage)
                1 -> OptimizationTab(state, onSetOptimizationConfig)
                2 -> BudgetsTab(state, onSetBudget)
                3 -> PricingTab(state, onSetPricingOverrides)
                4 -> ContextTab(state, onSetContextWindowOverride, onResetContextWindowOverrides)
                else -> SavingsTab(state, onResetSavings)
            }
        }
    }
}

// --- Overview -----------------------------------------------------------------------------

private enum class TimeWindow(val label: String, val millis: Long) {
    ALL("All", 0L),
    TODAY("24h", 86_400_000L),
    WEEK("7d", 7 * 86_400_000L),
    MONTH("30d", 30 * 86_400_000L),
}

@Composable
private fun OverviewTab(state: AppUiState, onClearUsage: () -> Unit) {
    var window by remember { mutableStateOf(TimeWindow.ALL) }
    var agentFilter by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf("") }
    var modelFilter by remember { mutableStateOf("") }
    var reliabilityFilter by remember { mutableStateOf<Reliability?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val overrides = state.tokenPricingOverrides
    val all = state.tokenUsageEntries
    val filtered = windowed(all, window.millis)
        .filter { agentFilter.isBlank() || it.agent == agentFilter }
        .filter { providerFilter.isBlank() || it.providerId == providerFilter }
        .filter { modelFilter.isBlank() || it.model == modelFilter }
        .filter { reliabilityFilter == null || it.reliability == reliabilityFilter }

    val pricingFor: (String, String) -> ModelPricing? = { pid, m -> TokenCalculator.resolvePricing(pid, m, overrides) }
    val totals = TokenSavings.totals(filtered, pricingFor)

    val agents = distinct(all.map { it.agent })
    val providers = distinct(all.map { it.providerId })
    val models = distinct(all.map { it.model })

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Filters
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeWindow.entries.forEach { w ->
                        FilterChip(selected = window == w, onClick = { window = w }, label = { Text(w.label) })
                    }
                }
                FilterDropdown("Agent", agents, agentFilter) { agentFilter = it }
                FilterDropdown("Provider", providers, providerFilter) { providerFilter = it }
                FilterDropdown("Model", models, modelFilter) { modelFilter = it }
                ReliabilityFilter(reliabilityFilter) { reliabilityFilter = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Showing ${filtered.size} request(s) · ${reliabilityCount(filtered, Reliability.ACTUAL)} actual · ${reliabilityCount(filtered, Reliability.ESTIMATED)} estimated · ${reliabilityCount(filtered, Reliability.NOT_TRACKED)} not tracked",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmClear = true }) { Text("Clear history") }
                }
            }
        }

        // Totals
        item { TotalsCard(totals) }

        // Request duration
        item {
            val withLatency = filtered.mapNotNull { it.latencyMillis }
            if (withLatency.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Request duration", fontWeight = FontWeight.SemiBold)
                        StatRow("Average latency", "${withLatency.average().toLong()} ms")
                        StatRow("Total latency", "${withLatency.sum()} ms")
                    }
                }
            }
        }

        // Time buckets
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("By period", fontWeight = FontWeight.SemiBold)
                    TimeBucketRow("Today", TokenSavings.totals(windowed(all, 86_400_000L), pricingFor))
                    TimeBucketRow("7 days", TokenSavings.totals(windowed(all, 7 * 86_400_000L), pricingFor))
                    TimeBucketRow("30 days", TokenSavings.totals(windowed(all, 30 * 86_400_000L), pricingFor))
                }
            }
        }

        // Breakdowns
        if (agents.isNotEmpty()) item { BreakdownCard("By agent", breakdown(filtered, overrides) { it.agent }) }
        if (providers.isNotEmpty()) item { BreakdownCard("By provider", breakdown(filtered, overrides) { it.providerId }) }
        if (models.isNotEmpty()) item { BreakdownCard("By model", breakdown(filtered, overrides) { it.model }) }
        if (distinct(all.map { it.projectId }).isNotEmpty()) {
            item { BreakdownCard("By project", breakdown(filtered, overrides) { it.projectId.ifBlank { "untracked project" } }) }
        }
        if (distinct(all.map { it.sessionId }).isNotEmpty()) {
            item { BreakdownCard("By session", breakdown(filtered, overrides) { it.sessionId.take(8).ifBlank { "untracked session" } }) }
        }

        if (all.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No usage recorded yet. Sidekick reports metered usage from its API responses; Claude Code reports its structured result-event usage; DeepSeek Harness and Antigravity do not expose token usage, so their sessions are recorded as \"not tracked\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear usage history?") },
            text = { Text("This deletes all recorded token usage. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onClearUsage(); confirmClear = false }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TotalsCard(totals: TokenTotals) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Total usage", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            StatRow("Input tokens", fmtTokens(totals.inputTokens))
            StatRow("Output tokens", fmtTokens(totals.outputTokens))
            StatRow("Cached input tokens", fmtTokens(totals.cachedReadTokens))
            StatRow("Cache write tokens", fmtTokens(totals.cacheWriteTokens))
            StatRow("Total tokens", fmtTokens(totals.totalTokens))
            StatRow("API calls", totals.requests.toString())
            StatRow("Estimated cost", if (totals.costKnown) fmtCost(totals.totalCost) else "${fmtCost(totals.totalCost)} (partial)")
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun TimeBucketRow(label: String, totals: TokenTotals) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${fmtTokens(totals.totalTokens)} tok · ${fmtCost(totals.totalCost)}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BreakdownCard(title: String, rows: List<BreakdownRow>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.key.ifBlank { "unknown" },
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${fmtTokens(row.totals.totalTokens)} tok · ${fmtCost(row.totals.totalCost)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(if (selected.isBlank()) "All" else selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("All") }, onClick = { onSelect(""); expanded = false })
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

// --- Optimization -------------------------------------------------------------------------

@Composable
private fun OptimizationTab(state: AppUiState, onSetConfig: (OptimizationConfig) -> Unit) {
    val config = state.tokenOptimizationConfig
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto Token Optimization", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (config.enabled) "Optimization is active" else "Optimization is off (tracking stays active)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = config.enabled, onCheckedChange = { onSetConfig(config.copy(enabled = it)) })
                    }
                    Text(
                        "When off, no agent behavior, context or model is changed. When on, only the safe optimizations you enable below are applied.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Optimization level", fontWeight = FontWeight.SemiBold)
                    OptimizationLevel.entries.forEach { level ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSetConfig(config.copy(level = level)) }) {
                            Column(Modifier.weight(1f)) {
                                Text(level.label, fontWeight = if (config.level == level) FontWeight.SemiBold else FontWeight.Normal)
                                Text(levelDescription(level), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (config.level == level) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enabled optimizations", fontWeight = FontWeight.SemiBold)
                    ToggleRow("Auto context compaction", config.autoCompact, "Summarize older messages when the context nears its limit.") {
                        onSetConfig(config.copy(autoCompact = it))
                    }
                    ToggleRow("Prompt caching", config.promptCaching, "Reuse cached prompt prefixes on providers that support it (e.g. Anthropic).") {
                        onSetConfig(config.copy(promptCaching = it))
                    }
                    ToggleRow("Smart model selection", config.smartModelSelection, "Suggest cheaper compatible models. Never auto-applies without confirmation.") {
                        onSetConfig(config.copy(smartModelSelection = it))
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Smart model selection limits", fontWeight = FontWeight.SemiBold)
                    var maxCost by remember(config.maxCostPerMillion) { mutableStateOf(config.maxCostPerMillion?.toString() ?: "") }
                    OutlinedTextField(
                        value = maxCost,
                        onValueChange = { maxCost = it },
                        label = { Text("Max cost per 1M tokens (USD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Minimum quality tier", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityTier.entries.forEach { tier ->
                            FilterChip(
                                selected = config.minQualityTier == tier,
                                onClick = { onSetConfig(config.copy(minQualityTier = tier)) },
                                label = { Text(tier.label) },
                            )
                        }
                    }
                    Button(onClick = {
                        val parsed = maxCost.toDoubleOrNull()?.takeIf { it > 0.0 }
                        onSetConfig(config.copy(maxCostPerMillion = parsed))
                    }) { Text("Apply max cost") }
                }
            }
        }

        item {
            TextButton(onClick = { onSetConfig(OptimizationConfig.DEFAULT) }) {
                Text("Reset to defaults", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun levelDescription(level: OptimizationLevel): String = when (level) {
    OptimizationLevel.LIGHT -> "Minimal: remove only obviously redundant content; keep nearly all context."
    OptimizationLevel.BALANCED -> "Summarize older messages, avoid repeating instructions, suggest cheaper models."
    OptimizationLevel.MAXIMUM -> "Aggressive but controlled: earlier summarization, maximum caching, strictest tracking. Never drops system/safety instructions."
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, description: String, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

// --- Budgets ------------------------------------------------------------------------------

@Composable
private fun BudgetsTab(state: AppUiState, onSetBudget: (BudgetConfig) -> Unit) {
    val budget = state.tokenBudget
    val overrides = state.tokenPricingOverrides
    val pricingFor: (String, String) -> ModelPricing? = { pid, m -> TokenCalculator.resolvePricing(pid, m, overrides) }

    val totalsAll = TokenSavings.totals(state.tokenUsageEntries, pricingFor)
    val spentToday = TokenSavings.totals(windowed(state.tokenUsageEntries, 86_400_000L), pricingFor).totalCost ?: 0.0
    val spentWeek = TokenSavings.totals(windowed(state.tokenUsageEntries, 7 * 86_400_000L), pricingFor).totalCost ?: 0.0
    val spentMonth = TokenSavings.totals(windowed(state.tokenUsageEntries, 30 * 86_400_000L), pricingFor).totalCost ?: 0.0

    var daily by remember(budget.dailyLimit) { mutableStateOf(budget.dailyLimit?.toString() ?: "") }
    var weekly by remember(budget.weeklyLimit) { mutableStateOf(budget.weeklyLimit?.toString() ?: "") }
    var monthly by remember(budget.monthlyLimit) { mutableStateOf(budget.monthlyLimit?.toString() ?: "") }
    var warnAt by remember(budget.warnAtPercent) { mutableStateOf(budget.warnAtPercent.toString()) }

    val providers = state.tokenUsageEntries.map { it.providerId }.filter { it.isNotBlank() }.distinct().sorted()
    val models = state.tokenUsageEntries.map { it.model }.filter { it.isNotBlank() }.distinct().sorted()
    val agents = state.tokenUsageEntries.map { it.agent }.filter { it.isNotBlank() }.distinct().sorted()
    val projects = state.tokenUsageEntries.map { it.projectId }.filter { it.isNotBlank() }.distinct().sorted()
    val providerLimitText = remember { mutableStateMapOf<String, String>() }
    val modelLimitText = remember { mutableStateMapOf<String, String>() }
    val agentLimitText = remember { mutableStateMapOf<String, String>() }
    val projectLimitText = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(budget.perProviderLimits, budget.perModelLimits, budget.perAgentLimits, budget.perProjectLimits) {
        providerLimitText.clear()
        budget.perProviderLimits.forEach { (k, v) -> providerLimitText[k] = v.toString() }
        modelLimitText.clear()
        budget.perModelLimits.forEach { (k, v) -> modelLimitText[k] = v.toString() }
        agentLimitText.clear()
        budget.perAgentLimits.forEach { (k, v) -> agentLimitText[k] = v.toString() }
        projectLimitText.clear()
        budget.perProjectLimits.forEach { (k, v) -> projectLimitText[k] = v.toString() }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Spend limits (USD)", fontWeight = FontWeight.SemiBold)
                    Text("Budgets are local estimates from recorded usage + your pricing. They are independent of any provider-side limit.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BudgetRow("Daily limit", spentToday, daily) { daily = it }
                    BudgetRow("Weekly limit", spentWeek, weekly) { weekly = it }
                    BudgetRow("Monthly limit", spentMonth, monthly) { monthly = it }
                    OutlinedTextField(
                        value = warnAt,
                        onValueChange = { warnAt = it },
                        label = { Text("Warn at % of limit") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("When a limit is reached", fontWeight = FontWeight.SemiBold)
                    BudgetReachedAction.entries.forEach { action ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSetBudget(budget.copy(reachedAction = action)) }) {
                            Column(Modifier.weight(1f)) {
                                Text(actionLabel(action), fontWeight = if (budget.reachedAction == action) FontWeight.SemiBold else FontWeight.Normal)
                                Text(actionDescription(action), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (budget.reachedAction == action) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (providers.isNotEmpty() || models.isNotEmpty() || agents.isNotEmpty() || projects.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Per-provider / per-model / per-agent / per-project limits", fontWeight = FontWeight.SemiBold)
                        Text("Leave blank for no limit.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        providers.forEach { pid ->
                            val spend = TokenSavings.totals(state.tokenUsageEntries.filter { it.providerId == pid }, pricingFor).totalCost ?: 0.0
                            BudgetRow("Provider: $pid", spend, providerLimitText[pid] ?: "") { providerLimitText[pid] = it }
                        }
                        models.forEach { m ->
                            val spend = TokenSavings.totals(state.tokenUsageEntries.filter { it.model == m }, pricingFor).totalCost ?: 0.0
                            BudgetRow("Model: $m", spend, modelLimitText[m] ?: "") { modelLimitText[m] = it }
                        }
                        agents.forEach { a ->
                            val spend = TokenSavings.totals(state.tokenUsageEntries.filter { it.agent == a }, pricingFor).totalCost ?: 0.0
                            BudgetRow("Agent: $a", spend, agentLimitText[a] ?: "") { agentLimitText[a] = it }
                        }
                        projects.forEach { p ->
                            val spend = TokenSavings.totals(state.tokenUsageEntries.filter { it.projectId == p }, pricingFor).totalCost ?: 0.0
                            BudgetRow("Project: $p", spend, projectLimitText[p] ?: "") { projectLimitText[p] = it }
                        }
                    }
                }
            }
        }

        item {
            Button(onClick = {
                val parsed = budget.copy(
                    dailyLimit = daily.toDoubleOrNull()?.takeIf { it > 0.0 },
                    weeklyLimit = weekly.toDoubleOrNull()?.takeIf { it > 0.0 },
                    monthlyLimit = monthly.toDoubleOrNull()?.takeIf { it > 0.0 },
                    warnAtPercent = warnAt.toIntOrNull()?.coerceIn(1, 100) ?: 80,
                    perProviderLimits = providerLimitText.mapNotNull { (k, v) -> v.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { k to it } }.toMap(),
                    perModelLimits = modelLimitText.mapNotNull { (k, v) -> v.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { k to it } }.toMap(),
                    perAgentLimits = agentLimitText.mapNotNull { (k, v) -> v.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { k to it } }.toMap(),
                    perProjectLimits = projectLimitText.mapNotNull { (k, v) -> v.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { k to it } }.toMap(),
                )
                onSetBudget(parsed)
            }) { Text("Save budget") }
        }

        if (totalsAll.entries > 0) {
            item {
                val dailyStatus = BudgetPolicy.status(spentToday, budget.dailyLimit, budget.warnAtPercent)
                val weeklyStatus = BudgetPolicy.status(spentWeek, budget.weeklyLimit, budget.warnAtPercent)
                val monthlyStatus = BudgetPolicy.status(spentMonth, budget.monthlyLimit, budget.warnAtPercent)
                BudgetStatusCard(dailyStatus, weeklyStatus, monthlyStatus)
            }
        }
    }
}

@Composable
private fun BudgetRow(label: String, spent: Double, value: String, onValueChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            Text("spent ${fmtCost(spent)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.width(120.dp),
            placeholder = { Text("no limit") },
        )
    }
}

@Composable
private fun BudgetStatusCard(daily: BudgetStatus, weekly: BudgetStatus, monthly: BudgetStatus) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Current status", fontWeight = FontWeight.SemiBold)
            Text("Daily: ${budgetLabel(daily)}", fontSize = 13.sp)
            Text("Weekly: ${budgetLabel(weekly)}", fontSize = 13.sp)
            Text("Monthly: ${budgetLabel(monthly)}", fontSize = 13.sp)
        }
    }
}

private fun budgetLabel(status: BudgetStatus): String = when (status) {
    BudgetStatus.UNDER -> "under limit"
    BudgetStatus.WARNING -> "approaching limit"
    BudgetStatus.REACHED -> "limit reached"
}

private fun actionLabel(action: BudgetReachedAction): String = when (action) {
    BudgetReachedAction.WARN_ONLY -> "Warn only"
    BudgetReachedAction.SUGGEST_CHEAPER -> "Suggest a cheaper model"
    BudgetReachedAction.INCREASE_OPTIMIZATION -> "Increase optimization"
    BudgetReachedAction.STOP_NEW_REQUESTS -> "Stop new cost-bearing requests"
}

private fun actionDescription(action: BudgetReachedAction): String = when (action) {
    BudgetReachedAction.WARN_ONLY -> "Only show a warning; never interrupt work."
    BudgetReachedAction.SUGGEST_CHEAPER -> "Propose a cheaper model before continuing."
    BudgetReachedAction.INCREASE_OPTIMIZATION -> "Apply stronger context optimization automatically."
    BudgetReachedAction.STOP_NEW_REQUESTS -> "Refuse new cost-bearing requests until the limit resets."
}

// --- Pricing ------------------------------------------------------------------------------

@Composable
private fun PricingTab(state: AppUiState, onSetOverrides: (Map<String, ModelPricing>) -> Unit) {
    val overrides = state.tokenPricingOverrides
    val updatedAt = state.tokenPricingUpdatedAtMillis

    // Models to surface: bundled references + existing overrides + models actually seen in usage.
    val seen = state.tokenUsageEntries.map { TokenPricingCatalog.key(it.providerId, it.model) }.toSet()
    val referenceKeys = TokenPricingCatalog.referencePrices.keys.toSet()
    val overrideKeys = overrides.keys.toSet()
    val allKeys = (referenceKeys + overrideKeys + seen).sorted()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Reference prices last updated: ${fmtDate(updatedAt)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                "Bundled prices are references only (marked \"verify with provider\"). Your overrides take priority. Leave a field blank to inherit the reference (or report \"not available\").",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(allKeys) { key ->
            PricingOverrideEditor(
                key = key,
                reference = TokenPricingCatalog.referencePrices[key],
                override = overrides[key],
                onSave = { updated ->
                    val next = overrides.toMutableMap()
                    if (updated == null || !updated.isKnown) next.remove(key) else next[key] = updated
                    onSetOverrides(next)
                },
                onDelete = { onSetOverrides(overrides - key) },
            )
        }
        item {
            TextButton(onClick = { onSetOverrides(emptyMap()) }) {
                Text("Reset all overrides", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PricingOverrideEditor(
    key: String,
    reference: ModelPricing?,
    override: ModelPricing?,
    onSave: (ModelPricing?) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var input by remember(key, override) { mutableStateOf(override?.inputPerMillion?.toString() ?: "") }
    var output by remember(key, override) { mutableStateOf(override?.outputPerMillion?.toString() ?: "") }
    var cachedRead by remember(key, override) { mutableStateOf(override?.cachedReadPerMillion?.toString() ?: "") }
    var cacheWrite by remember(key, override) { mutableStateOf(override?.cacheWritePerMillion?.toString() ?: "") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Column(Modifier.weight(1f)) {
                    Text(key, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            override != null -> "override: in ${override.inputPerMillion} / out ${override.outputPerMillion} / cached ${override.cachedReadPerMillion}"
                            reference != null -> "reference: in ${reference.inputPerMillion} / out ${reference.outputPerMillion}" + if (reference.reference) " (verify)" else ""
                            else -> "no known price"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                OutlinedTextField(input, { input = it }, label = { Text("Input $/1M") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(output, { output = it }, label = { Text("Output $/1M") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cachedRead, { cachedRead = it }, label = { Text("Cached read $/1M") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cacheWrite, { cacheWrite = it }, label = { Text("Cache write $/1M") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (override != null) {
                        TextButton(onClick = onDelete) { Text("Delete override", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val pricing = ModelPricing(
                            inputPerMillion = input.toDoubleOrNull()?.takeIf { it >= 0.0 },
                            outputPerMillion = output.toDoubleOrNull()?.takeIf { it >= 0.0 },
                            cachedReadPerMillion = cachedRead.toDoubleOrNull()?.takeIf { it >= 0.0 },
                            cacheWritePerMillion = cacheWrite.toDoubleOrNull()?.takeIf { it >= 0.0 },
                        )
                        onSave(if (pricing.isKnown) pricing else null)
                    }) { Text("Save") }
                }
            }
        }
    }
}

// --- Context ------------------------------------------------------------------------------

@Composable
private fun ContextTab(
    state: AppUiState,
    onSetOverride: (String, String, Long) -> Unit,
    onResetOverrides: () -> Unit,
) {
    val overrides = state.contextWindowOverrides
    val learned = state.contextWindowLearned

    // Models to surface: bundled references + models actually seen in usage + existing overrides/learned.
    val seenKeys = state.tokenUsageEntries.map { ContextWindowCatalog.key(it.providerId, it.model) }.toSet()
    val allKeys = (ContextWindowCatalog.referenceWindows.keys + seenKeys + overrides.keys + learned.keys).sorted()
    val parsedKeys = allKeys.map { key ->
        val parts = key.split('/', limit = 2)
        val pid = parts.getOrElse(0) { "custom" }
        val model = parts.getOrElse(1) { key }
        Triple(key, pid, model)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Context windows are documented references (verify with provider), user overrides, and limits learned from explicit provider errors. Unknown models show UNKNOWN and are never guessed.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(parsedKeys) { (key, pid, model) ->
            val reference = ContextWindowCatalog.referenceWindows[key]
            val learnedValue = learned[key]
            val overrideValue = overrides[key]
            val resolved = ContextUsageTracker.resolveMaxTokens(pid, model, overrides, learned)
            val latest = state.tokenUsageEntries.lastOrNull { ContextWindowCatalog.key(it.providerId, it.model) == key }
            ContextWindowEditor(
                key = key,
                reference = reference,
                learned = learnedValue,
                override = overrideValue,
                resolved = resolved,
                latestUsage = latest,
                onSave = { onSetOverride(pid, model, it) },
            )
        }
        item {
            TextButton(onClick = onResetOverrides) {
                Text("Reset all overrides", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ContextWindowEditor(
    key: String,
    reference: Long?,
    learned: Long?,
    override: Long?,
    resolved: Long?,
    latestUsage: TokenUsageEntry?,
    onSave: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var input by remember(key, override) { mutableStateOf(override?.toString() ?: "") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Column(Modifier.weight(1f)) {
                    Text(key, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("resolved: ${resolved?.let { fmtTokens(it) } ?: "UNKNOWN"}")
                            if (reference != null) append(" · reference ${fmtTokens(reference)} (verify)")
                            if (learned != null) append(" · learned ${fmtTokens(learned)}")
                            if (override != null) append(" · override ${fmtTokens(override)}")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (latestUsage != null && resolved != null && latestUsage.usage.totalTokens > 0L) {
                val pct = (latestUsage.usage.totalTokens.toDouble() / resolved * 100.0).coerceIn(0.0, 100.0)
                Text(
                    "Last request: ${fmtTokens(latestUsage.usage.totalTokens)} tokens of ${fmtTokens(resolved)} (${"%.0f".format(pct)}% used)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Max tokens (blank = inherit/unknown)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (override != null) {
                        TextButton(onClick = { onSave(0L) }) { Text("Clear override", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(input.toLongOrNull()?.coerceAtLeast(0L) ?: 0L) }) { Text("Save") }
                }
            }
        }
    }
}

// --- Savings ------------------------------------------------------------------------------

@Composable
private fun SavingsTab(state: AppUiState, onResetSavings: () -> Unit) {
    val overrides = state.tokenPricingOverrides
    val pricingFor: (String, String) -> ModelPricing? = { pid, m -> TokenCalculator.resolvePricing(pid, m, overrides) }
    val totals = TokenSavings.totals(state.tokenUsageEntries, pricingFor)
    val report: SavingsReport = TokenSavings.savingsReport(
        totals = totals,
        compactionTokensSaved = state.tokenCompactionSaved,
        modelChoiceTokensSaved = state.tokenModelChoiceSaved,
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Savings report", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    StatRow("Tokens used", fmtTokens(report.tokensUsed))
                    StatRow("Saved by cache", fmtTokens(report.tokensSavedByCache))
                    StatRow("Saved by compaction", fmtTokens(report.tokensSavedByCompaction))
                    StatRow("Saved by model choice", fmtTokens(report.tokensSavedByModelChoice))
                    StatRow("Total tokens saved", fmtTokens(report.totalTokensSaved))
                    StatRow("Estimated cost saved", report.estimatedCostSaved?.let { fmtCost(it) } ?: "not available")
                    HorizontalDivider()
                    Text(
                        "Savings are only shown when there is a reliable basis: cache savings come from metered cached-input tokens; compaction and model-choice savings come from explicitly recorded deltas. Estimated values are never presented as exact.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            TextButton(onClick = onResetSavings) {
                Text("Reset savings counters", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
