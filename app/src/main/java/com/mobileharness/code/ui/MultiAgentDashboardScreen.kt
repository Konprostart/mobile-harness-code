package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.hooks.HookAction
import com.mobileharness.code.hooks.HookEvent
import com.mobileharness.code.hooks.HookRule
import com.mobileharness.code.lsp.LspServerDefinition
import com.mobileharness.code.orchestration.DefaultAgentProfiles
import com.mobileharness.code.orchestration.TaskStatus
import com.mobileharness.code.testing.BuildSystem
import com.mobileharness.code.testing.TestPlanner
import com.mobileharness.code.token.TokenOptimizationServiceConfig
import com.mobileharness.code.workspace.WorkspaceGrant
import com.mobileharness.code.workspace.WorkspaceGroup
import com.mobileharness.code.workspace.WorkspacePermission
import com.mobileharness.code.workspace.WorkspaceRef
import com.mobileharness.code.workspace.WorkspaceRole
import java.util.UUID

/**
 * Multi-Agent Dashboard: orchestrator (goal → plan → run), agent capabilities, workspace
 * groups/grants, LSP servers, autonomous-testing history, and Sidekick hook rules. It presents the
 * Phase 6 subsystems and drives the real pure-logic engines behind them (no fabricated outcomes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAgentDashboardScreen(
    state: AppUiState,
    onClose: () -> Unit,
    onPlanOrchestration: (String) -> Unit,
    onAdvanceOrchestration: () -> Unit,
    onCancelOrchestration: () -> Unit,
    onSetOrchestratorEnabled: (Boolean) -> Unit,
    onSaveHookRule: (HookRule) -> Unit,
    onDeleteHookRule: (String) -> Unit,
    onToggleHookRule: (String, Boolean) -> Unit,
    onAddWorkspaceGroup: (WorkspaceGroup) -> Unit,
    onDeleteWorkspaceGroup: (String) -> Unit,
    onAddWorkspaceGrant: (WorkspaceGrant) -> Unit,
    onDeleteWorkspaceGrant: (String) -> Unit,
    onSaveLspServer: (LspServerDefinition) -> Unit,
    onDeleteLspServer: (String) -> Unit,
    onToggleLspServer: (String, Boolean) -> Unit,
    onClearTestingHistory: () -> Unit,
    onUpdateTokenOptimization: ((TokenOptimizationServiceConfig) -> TokenOptimizationServiceConfig) -> Unit = { it },
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Orchestrator", "Agents", "Workspaces", "LSP", "Testing", "Hooks")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi-Agent Dashboard", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title, fontSize = 12.sp) })
                }
            }
            when (selectedTab) {
                0 -> OrchestratorTab(state, onPlanOrchestration, onAdvanceOrchestration, onCancelOrchestration, onSetOrchestratorEnabled, onUpdateTokenOptimization)
                1 -> AgentsTab()
                2 -> WorkspacesTab(state, onAddWorkspaceGroup, onDeleteWorkspaceGroup, onAddWorkspaceGrant, onDeleteWorkspaceGrant)
                3 -> LspTab(state, onSaveLspServer, onDeleteLspServer, onToggleLspServer)
                4 -> TestingTab(state, onClearTestingHistory)
                5 -> HooksTab(state, onSaveHookRule, onDeleteHookRule, onToggleHookRule)
            }
        }
    }
}

@Composable
private fun OrchestratorTab(
    state: AppUiState,
    onPlan: (String) -> Unit,
    onAdvance: () -> Unit,
    onCancel: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onUpdateTokenOptimization: ((TokenOptimizationServiceConfig) -> TokenOptimizationServiceConfig) -> Unit,
) {
    var goal by remember { mutableStateOf(state.orchestration.goal) }
    val opt = state.orchestration.tokenOptimization
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Orchestrator", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Optional multi-agent mode. Default OFF — direct agent launches are unchanged.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = state.orchestration.enabled, onCheckedChange = onSetEnabled)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Token optimization", fontWeight = FontWeight.SemiBold)
                            Text("Central, optional token saver. Default OFF — orchestrator behavior is unchanged when off.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        Switch(checked = opt.enabled, onCheckedChange = { v -> onUpdateTokenOptimization { it.copy(enabled = v) } })
                    }
                    OptimizationToggle("Context shaping (compact handoff)", opt.contextShaping, opt.enabled) { v -> onUpdateTokenOptimization { it.copy(contextShaping = v) } }
                    OptimizationToggle("Budget gate (per-task/per-agent)", opt.budgetGating, opt.enabled) { v -> onUpdateTokenOptimization { it.copy(budgetGating = v) } }
                    OptimizationToggle("Model advisor (cheaper-model hints)", opt.modelAdvising, opt.enabled) { v -> onUpdateTokenOptimization { it.copy(modelAdvising = v) } }
                    if (opt.perTaskBudgetUsd != null) {
                        val budget = java.lang.String.format(java.util.Locale.ROOT, "%.2f", opt.perTaskBudgetUsd)
                        val spent = java.lang.String.format(java.util.Locale.ROOT, "%.4f", state.orchestration.taskSpendUsd)
                        Text("Per-task budget: \u0024$budget · spent \u0024$spent", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.orchestration.optimizationNote?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Text("Decompose one large goal into a dependency-ordered plan and run it through the real agents (Sidekick in-process; CLI agents via the existing runtime).", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        item {
            OutlinedTextField(value = goal, onValueChange = { goal = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Goal") }, placeholder = { Text("e.g. Build a crypto wallet app") })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPlan(goal) }, enabled = goal.isNotBlank()) { Text("Plan") }
                OutlinedButton(
                    onClick = onAdvance,
                    enabled = state.orchestration.enabled && state.orchestration.tasks.isNotEmpty() && !state.orchestration.done && !state.orchestration.running,
                ) { Text(if (state.orchestration.running) "Running…" else "Run next step") }
                TextButton(onClick = onCancel) { Text("Reset") }
            }
        }
        state.orchestration.message?.let { item { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) } }
        state.orchestration.lastError?.let { item { Text("Last error: $it", fontSize = 12.sp, color = statusColor(TaskStatus.FAILED)) } }
        item { Text("Tasks", fontWeight = FontWeight.SemiBold) }
        items(state.orchestration.tasks) { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(statusLabel(task.status), fontSize = 12.sp, color = statusColor(task.status))
                    }
                    Text(task.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (task.assignedAgentId != null) Text("Agent: ${task.assignedAgentId}", fontSize = 12.sp)
                    if (task.result.isNotBlank()) Text("Result: ${task.result}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    if (task.error.isNotBlank()) Text("Error: ${task.error}", fontSize = 12.sp, color = statusColor(TaskStatus.FAILED))
                    if (task.dependencies.isNotEmpty()) Text("Deps: ${task.dependencies.joinToString()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AgentsTab() {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Agent capabilities", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        items(DefaultAgentProfiles.PROFILES) { profile ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(profile.label, fontWeight = FontWeight.SemiBold)
                    Text(profile.capabilities.joinToString(", ") { it.name.lowercase() }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Supported agents: Claude Code, DeepSeek Harness, Antigravity, Sidekick. Capabilities are declared, not assumed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WorkspacesTab(
    state: AppUiState,
    onAddGroup: (WorkspaceGroup) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onAddGrant: (WorkspaceGrant) -> Unit,
    onDeleteGrant: (String) -> Unit,
) {
    var showAddGroup by remember { mutableStateOf(false) }
    var showAddGrant by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Workspace groups", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Button(onClick = { showAddGroup = true }) { Text("Add") }
            }
        }
        items(state.workspaceGroups) { group ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("project: ${group.projectId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { onDeleteGroup(group.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                    group.workspaces.forEach { ws ->
                        Text("• ${ws.name} [${ws.role.name.lowercase()}] ${ws.path}", fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Grants", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Button(onClick = { showAddGrant = true }) { Text("Add") }
            }
        }
        items(state.workspaceGrants) { grant ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${grant.agentId} → ${grant.permission.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("workspace=${grant.workspaceId ?: "any"} project=${grant.projectId ?: "any"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDeleteGrant(grant.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
    if (showAddGroup) {
        var name by remember { mutableStateOf("") }
        var projectId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddGroup = false },
            title = { Text("Add workspace group") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") })
                    OutlinedTextField(projectId, { projectId = it }, label = { Text("Project id") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddGroup(WorkspaceGroup(id = UUID.randomUUID().toString(), projectId = projectId.ifBlank { "default" }, name = name.ifBlank { "Group" }, workspaces = listOf(WorkspaceRef(name = "main", path = "/workspace", role = WorkspaceRole.MAIN))))
                    showAddGroup = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddGroup = false }) { Text("Cancel") } },
        )
    }
    if (showAddGrant) {
        var agent by remember { mutableStateOf("sidekick") }
        var permission by remember { mutableStateOf(WorkspacePermission.READ.name) }
        AlertDialog(
            onDismissRequest = { showAddGrant = false },
            title = { Text("Add grant") },
            text = {
                Column {
                    OutlinedTextField(agent, { agent = it }, label = { Text("Agent id") })
                    OutlinedTextField(permission, { permission = it }, label = { Text("Permission (READ/WRITE/EXECUTE/ADMIN)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddGrant(WorkspaceGrant(id = UUID.randomUUID().toString(), agentId = agent.ifBlank { "sidekick" }, permission = runCatching { WorkspacePermission.valueOf(permission.uppercase()) }.getOrDefault(WorkspacePermission.READ)))
                    showAddGrant = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddGrant = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LspTab(
    state: AppUiState,
    onSave: (LspServerDefinition) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LSP servers", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Button(onClick = { showAdd = true }) { Text("Add") }
            }
        }
        item { Text("Language servers are optional and install on demand in the PRoot runtime. Priority languages: Kotlin, Java, JS/TS, Python, Rust, Go, C/C++, JSON, YAML, HTML, CSS.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.lspServers) { server ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, fontWeight = FontWeight.SemiBold)
                        Text(server.command + " " + server.args.joinToString(" "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (server.extensions.isNotEmpty()) Text(server.extensions.keys.joinToString(" "), fontSize = 11.sp)
                        if (server.license.isNotBlank()) Text("license: ${server.license}", fontSize = 11.sp)
                    }
                    Switch(checked = server.enabled, onCheckedChange = { onToggle(server.name, it) })
                    IconButton(onClick = { onDelete(server.name) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var command by remember { mutableStateOf("") }
        var extension by remember { mutableStateOf(".kt") }
        var languageId by remember { mutableStateOf("kotlin") }
        var license by remember { mutableStateOf("Apache-2.0") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add LSP server") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") })
                    OutlinedTextField(command, { command = it }, label = { Text("Command") })
                    OutlinedTextField(extension, { extension = it }, label = { Text("Extension") })
                    OutlinedTextField(languageId, { languageId = it }, label = { Text("Language id") })
                    OutlinedTextField(license, { license = it }, label = { Text("License") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(LspServerDefinition(name = name.ifBlank { "server" }, command = command, extensions = mapOf(extension to languageId), languageIds = listOf(languageId), license = license))
                    showAdd = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TestingTab(state: AppUiState, onClear: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Autonomous testing & repair", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
        item { Text("Recognized build systems and their default test steps (Gradle, Maven, npm/pnpm/yarn, pytest, Cargo, Go, CMake).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(listOf(BuildSystem.GRADLE, BuildSystem.MAVEN, BuildSystem.NPM, BuildSystem.PYTEST, BuildSystem.CARGO, BuildSystem.GO, BuildSystem.CMAKE)) { bs ->
            val plan = TestPlanner.plan(bs)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${bs.label}: ${plan.steps.joinToString("; ") { it.command }}", fontSize = 12.sp)
                }
            }
        }
        item { Text("History", fontWeight = FontWeight.SemiBold) }
        if (state.testingHistory.isEmpty()) {
            item { Text("No test runs recorded yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.testingHistory) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${entry.buildSystem.label} · ${entry.result}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("${entry.passed}/${entry.stepsRun} passed, ${entry.failed} failed, ${entry.repairsApplied} repair(s)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HooksTab(
    state: AppUiState,
    onSave: (HookRule) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sidekick hooks", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Button(onClick = { showAdd = true }) { Text("Add") }
            }
        }
        item { Text("Hooks fire on Sidekick tool events; the most restrictive action wins. For Claude Code / DeepSeek Harness / Antigravity, their native hook systems are used unchanged.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.hookRules.isEmpty()) {
            item { Text("No hooks configured.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.hookRules) { rule ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${rule.event.name} → ${rule.action.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("matcher: ${rule.matcher}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule.id, it) })
                    IconButton(onClick = { onDelete(rule.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
    if (showAdd) {
        var event by remember { mutableStateOf(HookEvent.PRE_TOOL_USE.name) }
        var action by remember { mutableStateOf(HookAction.ALLOW.name) }
        var matcher by remember { mutableStateOf("*") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add hook rule") },
            text = {
                Column {
                    OutlinedTextField(event, { event = it }, label = { Text("Event") })
                    OutlinedTextField(action, { action = it }, label = { Text("Action (ALLOW/ASK/BLOCK/STOP…)") })
                    OutlinedTextField(matcher, { matcher = it }, label = { Text("Tool matcher") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ev = runCatching { HookEvent.valueOf(event.uppercase()) }.getOrDefault(HookEvent.PRE_TOOL_USE)
                    val act = runCatching { HookAction.valueOf(action.uppercase()) }.getOrDefault(HookAction.ALLOW)
                    onSave(HookRule(id = UUID.randomUUID().toString(), event = ev, matcher = matcher.ifBlank { "*" }, action = act))
                    showAdd = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OptimizationToggle(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "Planned"
    TaskStatus.QUEUED -> "Queued"
    TaskStatus.RUNNING -> "Running"
    TaskStatus.WAITING -> "Waiting"
    TaskStatus.SUCCEEDED -> "Completed"
    TaskStatus.FAILED -> "Failed"
    TaskStatus.CANCELLED -> "Cancelled"
    TaskStatus.BLOCKED -> "Blocked"
}

@Composable
private fun statusColor(status: TaskStatus) = when (status) {
    TaskStatus.SUCCEEDED -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
    TaskStatus.FAILED, TaskStatus.BLOCKED -> androidx.compose.ui.graphics.Color(0xFFC62828)
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
