package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import com.mobileharness.code.plugins.Plugin
import com.mobileharness.code.skills.InstalledSkill

/**
 * The Skills & Plugins manager: install, search, enable/disable and delete SKILL.md skills and
 * plugins. MCP servers continue to be managed from Settings → MCP (the existing screen); this
 * screen surfaces the installed-skill and installed-plugin state that the assistant and coding
 * agents can consume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsPluginsScreen(
    state: AppUiState,
    onClose: () -> Unit,
    onSetSkillEnabled: (String, Boolean) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onInstallSkillDir: (String) -> Unit,
    onInstallSkillZip: (String) -> Unit,
    onSetPluginEnabled: (String, Boolean) -> Unit,
    onDeletePlugin: (String) -> Unit,
    onInstallPluginJson: (String) -> Unit,
    onAdaptPluginDir: (String) -> Unit,
    onClearMessage: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills & Plugins", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showImport = true }) { Icon(Icons.Default.Add, "Import") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Skills (${state.installedSkills.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Plugins (${state.installedPlugins.size})") })
            }
            if (state.skillsPluginsMessage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.skillsPluginsMessage.orEmpty(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearMessage) { Text("Dismiss") }
                }
            }
            if (tab == 0) {
                SkillsTab(
                    skills = state.installedSkills,
                    query = query,
                    onQueryChange = { query = it },
                    category = category,
                    onCategoryChange = { category = it },
                    onSetEnabled = onSetSkillEnabled,
                    onDelete = onDeleteSkill,
                )
            } else {
                PluginsTab(
                    plugins = state.installedPlugins,
                    query = query,
                    onQueryChange = { query = it },
                    onSetEnabled = onSetPluginEnabled,
                    onDelete = onDeletePlugin,
                )
            }
        }
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onInstallSkillDir = { onInstallSkillDir(it); showImport = false },
            onInstallSkillZip = { onInstallSkillZip(it); showImport = false },
            onInstallPluginJson = { onInstallPluginJson(it); showImport = false },
            onAdaptPluginDir = { onAdaptPluginDir(it); showImport = false },
        )
    }
}

@Composable
private fun SkillsTab(
    skills: List<InstalledSkill>,
    query: String,
    onQueryChange: (String) -> Unit,
    category: String?,
    onCategoryChange: (String?) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val categories = remember(skills) { skills.map { it.category }.distinct().sorted() }
    val filtered = remember(skills, query, category) {
        skills.filter { skill ->
            (category == null || skill.category == category) &&
                (query.isBlank() || skill.name.contains(query, true) ||
                    skill.description.contains(query, true) || skill.tags.any { it.contains(query, true) })
        }
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search skills") },
                singleLine = true,
            )
        }
        if (categories.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category == null, onClick = { onCategoryChange(null) }, label = { Text("All") })
                    categories.forEach { c ->
                        FilterChip(selected = category == c, onClick = { onCategoryChange(if (category == c) null else c) }, label = { Text(c) })
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item { Text("No skills installed. Tap + to import a SKILL.md skill.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(filtered, key = { it.id }) { skill ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${skill.category} · v${skill.version} · ${skill.source.name.lowercase()}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = skill.enabled, onCheckedChange = { onSetEnabled(skill.id, it) })
                    }
                    Text(skill.description, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (skill.tags.isNotEmpty()) {
                        Text("Tags: ${skill.tags.joinToString(", ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Files: ${skill.files.size} · ${skill.sourceRef}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onDelete(skill.id) }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginsTab(
    plugins: List<Plugin>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val filtered = remember(plugins, query) {
        plugins.filter { plugin ->
            query.isBlank() || plugin.manifest.name.contains(query, true) ||
                plugin.manifest.description.contains(query, true) || plugin.manifest.format.contains(query, true)
        }
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search plugins") },
                singleLine = true,
            )
        }
        if (filtered.isEmpty()) {
            item { Text("No plugins installed. Tap + to add a plugin.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(filtered, key = { it.manifest.id }) { plugin ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plugin.manifest.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "v${plugin.manifest.version} · ${plugin.manifest.format.ifBlank { plugin.source.name.lowercase() }}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = plugin.enabled, onCheckedChange = { onSetEnabled(plugin.manifest.id, it) })
                    }
                    Text(plugin.manifest.description, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (plugin.manifest.tools.isNotEmpty()) {
                        Text("Tools: ${plugin.manifest.tools.joinToString(", ") { it.name }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (plugin.manifest.runtimes.isNotEmpty()) {
                        Text("Runtimes: ${plugin.manifest.runtimes.joinToString(", ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (plugin.manifest.permissions.isNotEmpty()) {
                        Text("Permissions: ${plugin.manifest.permissions.joinToString(", ") { it.name.lowercase() }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDelete(plugin.manifest.id) }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onInstallSkillDir: (String) -> Unit,
    onInstallSkillZip: (String) -> Unit,
    onInstallPluginJson: (String) -> Unit,
    onAdaptPluginDir: (String) -> Unit,
) {
    var mode by remember { mutableStateOf("dir") }
    var path by remember { mutableStateOf("") }
    var json by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "dir", onClick = { mode = "dir" }, label = { Text("Skill dir") })
                    FilterChip(selected = mode == "zip", onClick = { mode = "zip" }, label = { Text("Skill ZIP") })
                    FilterChip(selected = mode == "plugin", onClick = { mode = "plugin" }, label = { Text("Plugin JSON") })
                    FilterChip(selected = mode == "adapt", onClick = { mode = "adapt" }, label = { Text("Adapt dir") })
                }
                when (mode) {
                    "plugin" -> OutlinedTextField(
                        value = json,
                        onValueChange = { json = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        label = { Text("plugin.json") },
                        placeholder = { Text("{\"id\":\"...\",\"name\":\"...\",\"version\":\"1.0.0\"}") },
                    )
                    else -> OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (mode == "zip") "ZIP path" else "Directory path") },
                        singleLine = true,
                    )
                }
                Text(
                    when (mode) {
                        "dir" -> "A directory containing SKILL.md."
                        "zip" -> "A ZIP archive of SKILL.md skill folders."
                        "plugin" -> "A plugin.json manifest (Mobile Harness / AndroidHarness format)."
                        else -> "A Claude Code / Codex / Cursor / Gemini bundle directory; it is adapted to the internal format."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                when (mode) {
                    "dir" -> if (path.isNotBlank()) onInstallSkillDir(path)
                    "zip" -> if (path.isNotBlank()) onInstallSkillZip(path)
                    "plugin" -> if (json.isNotBlank()) onInstallPluginJson(json)
                    "adapt" -> if (path.isNotBlank()) onAdaptPluginDir(path)
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
