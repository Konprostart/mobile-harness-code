package com.mobileharness.code.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.mobileharness.code.R
import com.mobileharness.code.automation.PromptRun
import com.mobileharness.code.automation.PromptRunStatus
import com.mobileharness.code.automation.Recurrence
import com.mobileharness.code.automation.RunMode
import com.mobileharness.code.automation.ScheduledTask
import com.mobileharness.code.model.AiProvider
import com.mobileharness.code.model.AiProviderCatalog
import com.mobileharness.code.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Maps the short task-kind string used by the automation layer to a UI label. */
private data class AgentOption(val taskKind: String, val label: String)

private val AGENT_OPTIONS = listOf(
    AgentOption("claude", "Claude Code"),
    AgentOption("deepseek", "DeepSeek Harness"),
    AgentOption("antigravity", "Antigravity"),
    AgentOption("sidekick", "Sidekick"),
)

private fun agentLabel(taskKind: String): String =
    AGENT_OPTIONS.firstOrNull { it.taskKind == taskKind }?.label ?: taskKind

/** A schedule preset expressed as a recurrence + a minute value. */
private data class SchedulePreset(val label: String, val recurrence: Recurrence, val minutes: Long)

private val ONCE_PRESETS = listOf(
    SchedulePreset("In 30 minutes", Recurrence.ONE_SHOT, 30),
    SchedulePreset("In 1 hour", Recurrence.ONE_SHOT, 60),
    SchedulePreset("In 6 hours", Recurrence.ONE_SHOT, 6 * 60),
    SchedulePreset("In 24 hours", Recurrence.ONE_SHOT, 24 * 60),
)

private val INTERVAL_PRESETS = listOf(
    SchedulePreset("Every 30 minutes", Recurrence.INTERVAL, 30),
    SchedulePreset("Every 1 hour", Recurrence.INTERVAL, 60),
    SchedulePreset("Every 6 hours", Recurrence.INTERVAL, 6 * 60),
    SchedulePreset("Daily", Recurrence.INTERVAL, 24 * 60),
    SchedulePreset("Weekly", Recurrence.INTERVAL, 7 * 24 * 60),
)

private fun formatWhen(millis: Long): String {
    if (millis <= 0L) return "Never"
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
}

/**
 * Full-screen AUTO PROMPT library: a master on/off switch, a searchable list of saved prompts with
 * reordering and run status, and an editor for name/description/instructions/agent/provider/model/
 * schedule. Manual RUN is always available; scheduled runs are gated by the master + per-prompt
 * switches in the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoPromptScreen(
    state: AppUiState,
    onClose: () -> Unit,
    onSetMaster: (Boolean) -> Unit,
    onSave: (ScheduledTask) -> Boolean,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
    onRun: (String) -> Unit,
    onClearHistory: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var expandedHistoryId by remember { mutableStateOf<String?>(null) }

    val tasks = state.autoPromptTasks
    val filtered = tasks.filter { task ->
        val q = query.trim().lowercase()
        q.isBlank() ||
            task.title.contains(q, true) ||
            task.description.contains(q, true) ||
            task.prompt.contains(q, true) ||
            agentLabel(task.agentKind).contains(q, true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.autoprompt_library_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Master switch
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.autoprompt_library_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.autoPromptSystemEnabled) stringResource(R.string.autoprompt_master_on)
                            else stringResource(R.string.autoprompt_master_off),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.autoPromptSystemEnabled, onCheckedChange = onSetMaster)
                }
                Text(
                    stringResource(R.string.autoprompt_master_note),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Search + add
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.autoprompt_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) }
                        }
                    },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { creating = true; editing = null }) {
                    Icon(Icons.Default.Add, stringResource(R.string.autoprompt_add))
                }
            }
            Text(
                stringResource(R.string.autoprompt_count, filtered.size),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.autoprompt_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(filtered, key = { _, t -> t.id }) { index, task ->
                        AutoPromptCard(
                            task = task,
                            index = index,
                            total = filtered.size,
                            expanded = expandedHistoryId == task.id,
                            history = state.autoPromptHistory.filter { it.promptId == task.id },
                            onToggleExpanded = { expandedHistoryId = if (expandedHistoryId == task.id) null else task.id },
                            onEdit = { editing = task },
                            onDelete = { confirmDeleteId = task.id },
                            onDuplicate = { onDuplicate(task.id) },
                            onToggleEnabled = { onToggleEnabled(task.id, it) },
                            onMoveUp = { onReorder(reorderIds(filtered, index, index - 1)) },
                            onMoveDown = { onReorder(reorderIds(filtered, index, index + 1)) },
                            onRun = { onRun(task.id) },
                            onClearHistory = { onClearHistory(task.id) },
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        AutoPromptEditorDialog(
            initial = editing,
            projects = state.projects,
            onDismiss = { creating = false; editing = null },
            onSave = { task ->
                val ok = onSave(task)
                if (ok) { creating = false; editing = null }
                ok
            },
        )
    }

    if (confirmDeleteId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text(stringResource(R.string.autoprompt_confirm_delete_title)) },
            text = { Text(stringResource(R.string.autoprompt_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(confirmDeleteId!!)
                    confirmDeleteId = null
                }) { Text(stringResource(R.string.autoprompt_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(R.string.autoprompt_cancel)) }
            },
        )
    }
}

/** Maps an existing task back to the closest schedule preset index (0 = first one-shot preset). */
private fun initialPresetIndex(initial: ScheduledTask?): Int {
    if (initial == null || initial.runMode != RunMode.SCHEDULED) return 0
    if (initial.recurrence == Recurrence.INTERVAL) {
        val minutes = initial.intervalMillis / 60_000L
        val idx = INTERVAL_PRESETS.indexOfFirst { it.minutes == minutes }
        if (idx >= 0) return ONCE_PRESETS.size + idx
    }
    return 0
}

private fun reorderIds(list: List<ScheduledTask>, from: Int, to: Int): List<String> {
    if (from < 0 || to < 0 || from >= list.size || to >= list.size) return list.map { it.id }
    val ids = list.map { it.id }.toMutableList()
    val item = ids.removeAt(from)
    ids.add(to, item)
    return ids
}

@Composable
private fun AutoPromptCard(
    task: ScheduledTask,
    index: Int,
    total: Int,
    expanded: Boolean,
    history: List<PromptRun>,
    onToggleExpanded: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRun: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val running = task.lastRunStatus == PromptRunStatus.RUNNING.name
    val statusColor = when (task.lastRunStatus) {
        PromptRunStatus.SUCCESS.name -> Color(0xFF2E7D32)
        PromptRunStatus.FAILED.name -> MaterialTheme.colorScheme.error
        PromptRunStatus.RUNNING.name -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title.ifBlank { agentLabel(task.agentKind) },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.description.isNotBlank()) {
                        Text(
                            task.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(agentLabel(task.agentKind), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (task.providerId.isNotBlank()) {
                            Text(" · ${task.providerId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (task.model.isNotBlank()) {
                            Text(" · ${task.model}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Switch(checked = task.enabled, onCheckedChange = onToggleEnabled)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.autoprompt_last_run), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    " ${formatWhen(task.lastRunMillis)}",
                    fontSize = 11.sp,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.autoprompt_next_run), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (task.runMode == RunMode.SCHEDULED && task.enabled) " ${formatWhen(task.nextRunMillis)}" else " —",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRun, enabled = !running) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (running) stringResource(R.string.autoprompt_status_running) else stringResource(R.string.autoprompt_run))
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.autoprompt_edit))
                }
                IconButton(onClick = onDuplicate) { Icon(Icons.Default.ContentCopy, stringResource(R.string.autoprompt_duplicate)) }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(task.prompt))
                }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.autoprompt_copy)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.autoprompt_delete), tint = MaterialTheme.colorScheme.error) }
                if (index > 0) IconButton(onClick = onMoveUp) { Icon(Icons.Default.ArrowUpward, null) }
                if (index < total - 1) IconButton(onClick = onMoveDown) { Icon(Icons.Default.ArrowDownward, null) }
                IconButton(onClick = onToggleExpanded) { Icon(Icons.Default.History, stringResource(R.string.autoprompt_history)) }
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (history.isEmpty()) {
                    Text(stringResource(R.string.autoprompt_history_empty), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.autoprompt_history), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    history.take(10).forEach { run ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            val runColor = when (run.status) {
                                PromptRunStatus.SUCCESS -> Color(0xFF2E7D32)
                                PromptRunStatus.FAILED -> MaterialTheme.colorScheme.error
                                PromptRunStatus.RUNNING -> MaterialTheme.colorScheme.primary
                            }
                            Box(Modifier.width(8.dp).height(8.dp).padding(top = 4.dp)) {
                                Text("•", color = runColor, fontSize = 12.sp)
                            }
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(
                                    "${run.status.name.lowercase().replaceFirstChar { it.uppercase() }} · ${formatWhen(run.startedAtMillis)}",
                                    fontSize = 11.sp,
                                    color = runColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val body = run.resultText.ifBlank { run.errorText }
                                if (body.isNotBlank()) {
                                    Text(body.take(220), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    TextButton(onClick = onClearHistory) { Text(stringResource(R.string.autoprompt_delete), fontSize = 12.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoPromptEditorDialog(
    initial: ScheduledTask?,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Boolean,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var description by remember(initial?.id) { mutableStateOf(initial?.description ?: "") }
    var instructions by remember(initial?.id) { mutableStateOf(initial?.prompt ?: "") }
    var agentKind by remember(initial?.id) { mutableStateOf(initial?.agentKind ?: "claude") }
    var providerId by remember(initial?.id) { mutableStateOf(initial?.providerId ?: "") }
    var model by remember(initial?.id) { mutableStateOf(initial?.model ?: "") }
    var projectId by remember(initial?.id) { mutableStateOf(initial?.projectId ?: "") }
    var runMode by remember(initial?.id) { mutableStateOf(initial?.runMode ?: RunMode.MANUAL) }
    var selectedPresetIndex by remember(initial?.id) { mutableStateOf(initialPresetIndex(initial)) }
    var error by remember(initial?.id) { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val isSidekick = agentKind == "sidekick"
    val providerOptions = listOf(AiProvider(id = "", name = "Default", category = com.mobileharness.code.model.AiProviderCategory.CUSTOM, baseUrl = "", wireProtocol = com.mobileharness.code.model.AiWireProtocol.ANTHROPIC_MESSAGES, defaultModel = "", authType = com.mobileharness.code.model.AiAuthType.API_KEY, apiFormats = emptySet(), capabilities = emptySet(), docsUrl = "", official = false)) + AiProviderCatalog.current()

    fun buildTask(): ScheduledTask {
        val isScheduled = runMode == RunMode.SCHEDULED
        val preset = if (isScheduled) {
            val presets = if (selectedPresetIndex < ONCE_PRESETS.size) ONCE_PRESETS else INTERVAL_PRESETS
            presets.getOrNull(if (selectedPresetIndex < ONCE_PRESETS.size) selectedPresetIndex else selectedPresetIndex - ONCE_PRESETS.size)
        } else null
        val recurrence = preset?.recurrence ?: Recurrence.ONE_SHOT
        val intervalMillis = if (recurrence == Recurrence.INTERVAL) preset!!.minutes * 60_000L else 0L
        val now = System.currentTimeMillis()
        val nextRunMillis = when {
            !isScheduled -> 0L
            recurrence == Recurrence.INTERVAL -> now + intervalMillis
            else -> now + (preset?.minutes ?: 60L) * 60_000L
        }
        val effectiveProjectId = when {
            isSidekick -> projectId
            projectId.isNotBlank() -> projectId
            else -> ""
        }
        return ScheduledTask(
            id = initial?.id ?: UUID.randomUUID().toString(),
            agentKind = agentKind,
            projectId = effectiveProjectId,
            prompt = instructions.trim(),
            recurrence = recurrence,
            nextRunMillis = nextRunMillis,
            intervalMillis = intervalMillis,
            enabled = initial?.enabled ?: true,
            title = name.trim(),
            description = description.trim(),
            providerId = providerId,
            model = model.trim(),
            runMode = runMode,
            lastRunMillis = initial?.lastRunMillis ?: 0L,
            lastRunStatus = initial?.lastRunStatus ?: "",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.autoprompt_add) else stringResource(R.string.autoprompt_edit)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.autoprompt_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.autoprompt_description_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text(stringResource(R.string.autoprompt_instructions_label)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
                item {
                    DropdownField(
                        label = stringResource(R.string.autoprompt_agent_label),
                        selected = agentLabel(agentKind),
                        options = AGENT_OPTIONS.map { it.label },
                        onSelect = { index ->
                            agentKind = AGENT_OPTIONS[index].taskKind
                            if (agentKind == "sidekick") projectId = ""
                        },
                    )
                }
                if (!isSidekick) {
                    item {
                        DropdownField(
                            label = stringResource(R.string.autoprompt_provider_label),
                            selected = providerOptions.firstOrNull { it.id == providerId }?.name ?: "Default",
                            options = providerOptions.map { it.name },
                            onSelect = { index -> providerId = providerOptions[index].id },
                        )
                    }
                    item {
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text(stringResource(R.string.autoprompt_model_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (!isSidekick) {
                    item {
                        DropdownField(
                            label = "Project",
                            selected = projects.firstOrNull { it.id == projectId }?.name ?: "Select a project",
                            options = projects.map { it.name },
                            onSelect = { index -> projectId = projects[index].id },
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = runMode == RunMode.MANUAL, onClick = { runMode = RunMode.MANUAL }, label = { Text(stringResource(R.string.autoprompt_run_mode_manual)) })
                        FilterChip(selected = runMode == RunMode.SCHEDULED, onClick = { runMode = RunMode.SCHEDULED }, label = { Text(stringResource(R.string.autoprompt_run_mode_scheduled)) })
                    }
                }
                if (runMode == RunMode.SCHEDULED) {
                    item { Text(stringResource(R.string.autoprompt_schedule_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ONCE_PRESETS.forEachIndexed { index, preset ->
                                FilterChip(selected = selectedPresetIndex == index, onClick = { selectedPresetIndex = index }, label = { Text(preset.label.take(14), fontSize = 11.sp) })
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            INTERVAL_PRESETS.forEachIndexed { index, preset ->
                                val actualIndex = ONCE_PRESETS.size + index
                                FilterChip(selected = selectedPresetIndex == actualIndex, onClick = { selectedPresetIndex = actualIndex }, label = { Text(preset.label.take(14), fontSize = 11.sp) })
                            }
                        }
                    }
                }
                if (error != null) {
                    item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val task = buildTask()
                if (!isSidekick && task.projectId.isBlank()) {
                    error = context.getString(R.string.autoprompt_no_project)
                } else if (task.prompt.isBlank()) {
                    error = "Instructions are required."
                } else if (!onSave(task)) {
                    error = "Could not save: check the prompt length or schedule interval."
                }
            }) { Text(stringResource(R.string.autoprompt_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.autoprompt_cancel)) }
        },
    )
}

@Composable
private fun DropdownField(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { expanded = true }) { Text("▾") }
            },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        )
        Box {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        },
                        leadingIcon = if (option == selected) ({ Icon(Icons.Default.Check, null) }) else null,
                    )
                }
            }
        }
    }
}
