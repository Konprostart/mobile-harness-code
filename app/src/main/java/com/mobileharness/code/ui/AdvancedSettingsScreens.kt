package com.mobileharness.code.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.R
import com.mobileharness.code.mcp.McpServerConfig
import com.mobileharness.code.mcp.McpTool
import com.mobileharness.code.mcp.McpToolConfirmMode
import com.mobileharness.code.mcp.McpTransport
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.network.ServerSyncConfig
import com.mobileharness.code.network.ServerSyncMode
import com.mobileharness.code.network.ssh.SshAuthType
import com.mobileharness.code.network.ssh.SshProfile
import com.mobileharness.code.runtime.ReleasePipelineStage
import com.mobileharness.code.sidekick.RagDocument
import com.mobileharness.code.sidekick.RagIndex

@Composable
internal fun releaseStageLabel(stage: ReleasePipelineStage): String = when (stage) {
    ReleasePipelineStage.BUILD -> stringResource(R.string.release_stage_build)
    ReleasePipelineStage.ZIPALIGN -> stringResource(R.string.release_stage_zipalign)
    ReleasePipelineStage.SIGN -> stringResource(R.string.release_stage_sign)
    ReleasePipelineStage.VERIFY -> stringResource(R.string.release_stage_verify)
    ReleasePipelineStage.CHECKSUM -> stringResource(R.string.release_stage_checksum)
    ReleasePipelineStage.COMPLETE -> stringResource(R.string.release_stage_complete)
    ReleasePipelineStage.FAILED -> stringResource(R.string.release_stage_failed)
}

/**
 * Release & Signing: choose a keystore, enter an alias and passwords (in-memory only, passed
 * through `env:`), run the local Build → Sign → Verify → Checksum pipeline, then save or
 * SAF-export the signed APK for manual Signiffia signing. Passwords are never stored or logged.
 */
@Composable
fun ReleaseSigningSettings(
    state: AppUiState,
    onPickKeystore: (Uri) -> Unit,
    onSetAlias: (String) -> Unit,
    onClearKeystore: () -> Unit,
    onRunPipeline: (storePassword: String, keyPassword: String) -> Unit,
    onSaveDownloads: () -> Unit,
    onExport: (Uri) -> Unit,
) {
    val ui = state.releaseUi
    var storePassword by rememberSaveable { mutableStateOf("") }
    var keyPassword by rememberSaveable { mutableStateOf("") }

    val keystoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickKeystore)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri -> uri?.let(onExport) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.release_signing_password_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { keystoreLauncher.launch(arrayOf("*/*")) },
                enabled = !ui.running,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Folder, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.release_signing_keystore_choose),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ui.keystoreDisplayName.isNotBlank()) {
                IconButton(onClick = onClearKeystore) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                }
            }
        }
        Text(
            if (ui.keystoreDisplayName.isBlank()) stringResource(R.string.release_signing_keystore_none)
            else stringResource(R.string.release_signing_keystore_label) + ": " + ui.keystoreDisplayName,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = ui.keyAlias,
            onValueChange = onSetAlias,
            label = { Text(stringResource(R.string.release_signing_alias_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = storePassword,
            onValueChange = { storePassword = it },
            label = { Text(stringResource(R.string.release_signing_store_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = keyPassword,
            onValueChange = { keyPassword = it },
            label = { Text(stringResource(R.string.release_signing_key_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onRunPipeline(storePassword, keyPassword) },
            enabled = !ui.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (ui.running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Build, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(
                if (ui.running) stringResource(R.string.release_signing_running)
                else stringResource(R.string.release_signing_run),
            )
        }

        ui.message?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                color = if (ui.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }

        if (ui.stages.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.stages.forEach { stage ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (stage.success) Icons.Default.Check else Icons.Default.Close,
                                null,
                                Modifier.size(15.dp),
                                tint = if (stage.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(releaseStageLabel(stage.stage), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        ui.sha256?.let { sha ->
            val clipboard = LocalClipboardManager.current
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(sha)) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.release_signing_sha256_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sha, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (ui.signedApkPath != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSaveDownloads, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.release_signing_save_downloads), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch("mobile-harness-signed.apk") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.release_signing_export_signiffia), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Optional, opt-in server sync settings. The user chooses between their own SSH server and a
 * local/Android `dukoi.com` server; the dukoi token is stored encrypted in the ApiKeyVault and
 * is never shown back in the UI. Sync is off by default and contains no telemetry.
 */
@Composable
fun ServerSyncSettings(
    config: ServerSyncConfig,
    dukoiTokenConfigured: Boolean,
    onSave: (ServerSyncConfig) -> Unit,
    onClearToken: () -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(config.enabled) }
    var modeName by rememberSaveable { mutableStateOf(config.mode.name) }
    var sshHost by rememberSaveable { mutableStateOf(config.sshHost) }
    var sshPort by rememberSaveable { mutableStateOf(config.sshPort.toString()) }
    var sshUser by rememberSaveable { mutableStateOf(config.sshUser) }
    var sshKeyPath by rememberSaveable { mutableStateOf(config.sshKeyPath) }
    var sshRemoteDir by rememberSaveable { mutableStateOf(config.sshRemoteDir) }
    var dukoiBaseUrl by rememberSaveable { mutableStateOf(config.dukoiBaseUrl) }
    var dukoiToken by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.server_sync_enabled), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.server_sync_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        if (enabled) {
            Text(stringResource(R.string.server_sync_mode_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { modeName = ServerSyncMode.SSH.name }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.server_sync_mode_ssh), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { modeName = ServerSyncMode.DUKOI.name }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.server_sync_mode_dukoi), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (modeName == ServerSyncMode.SSH.name) {
                OutlinedTextField(sshHost, { sshHost = it }, label = { Text(stringResource(R.string.server_sync_ssh_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    sshPort,
                    { sshPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.server_sync_ssh_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(sshUser, { sshUser = it }, label = { Text(stringResource(R.string.server_sync_ssh_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sshKeyPath, { sshKeyPath = it }, label = { Text(stringResource(R.string.server_sync_ssh_key_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sshRemoteDir, { sshRemoteDir = it }, label = { Text(stringResource(R.string.server_sync_ssh_remote_dir)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(dukoiBaseUrl, { dukoiBaseUrl = it }, label = { Text(stringResource(R.string.server_sync_dukoi_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = dukoiToken,
                    onValueChange = { dukoiToken = it },
                    label = {
                        Text(
                            if (dukoiTokenConfigured) stringResource(R.string.server_sync_dukoi_token_saved)
                            else stringResource(R.string.server_sync_dukoi_token),
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (dukoiTokenConfigured) {
                    TextButton(onClick = onClearToken) {
                        Text(stringResource(R.string.server_sync_clear_token), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Button(
            onClick = {
                onSave(
                    ServerSyncConfig(
                        enabled = enabled,
                        mode = runCatching { ServerSyncMode.valueOf(modeName) }.getOrDefault(ServerSyncMode.SSH),
                        sshHost = sshHost.trim(),
                        sshPort = sshPort.toIntOrNull() ?: 22,
                        sshUser = sshUser.trim(),
                        sshKeyPath = sshKeyPath.trim(),
                        sshRemoteDir = sshRemoteDir.trim(),
                        dukoiBaseUrl = dukoiBaseUrl.trim(),
                        dukoiToken = dukoiToken.trim(),
                    ),
                )
                saved = true
                dukoiToken = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.server_sync_save)) }

        if (saved) {
            Text(stringResource(R.string.server_sync_saved), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        Text(
            stringResource(R.string.server_sync_opt_in_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Sidekick assistant settings: the global floating button, an optional independent (more
 * powerful) provider/model, and bounded background research over the user's allowed sources.
 * Everything is opt-in and off by default; the provider's API key is reused from the vault.
 */
@Composable
fun SidekickSettings(
    floatingButton: Boolean,
    useCustomProvider: Boolean,
    backgroundResearch: Boolean,
    researchSources: String,
    provider: ProviderProfile,
    fallbackEnabled: Boolean,
    fallbackModel: String,
    localModelEnabled: Boolean,
    localModelBaseUrl: String,
    localModelName: String,
    onSetFloatingButton: (Boolean) -> Unit,
    onSetUseCustomProvider: (Boolean) -> Unit,
    onSetBackgroundResearch: (Boolean) -> Unit,
    onSetResearchSources: (String) -> Unit,
    onSaveProvider: (ProviderProfile) -> Unit,
    onSetFallbackEnabled: (Boolean) -> Unit,
    onSetFallbackModel: (String) -> Unit,
    onSetLocalModelEnabled: (Boolean) -> Unit,
    onSetLocalModelBaseUrl: (String) -> Unit,
    onSetLocalModelName: (String) -> Unit,
    onLaunch: () -> Unit,
) {
    var selectedKind by rememberSaveable { mutableStateOf(provider.kind.name) }
    var model by rememberSaveable { mutableStateOf(provider.model) }
    var baseUrl by rememberSaveable { mutableStateOf(provider.baseUrl) }
    var kindMenuOpen by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }

    val providerChoices = ProviderKind.entries.filterNot { it == ProviderKind.CLAUDE }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onLaunch, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Build, null)
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.sidekick_launch))
        }

        Text(
            stringResource(R.string.sidekick_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sidekick_floating_button), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.sidekick_floating_button_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = floatingButton, onCheckedChange = onSetFloatingButton)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sidekick_use_custom_provider), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.sidekick_use_custom_provider_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = useCustomProvider, onCheckedChange = onSetUseCustomProvider)
        }

        if (useCustomProvider) {
            Text(stringResource(R.string.sidekick_provider_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(onClick = { kindMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        providerChoices.firstOrNull { it.name == selectedKind }?.title ?: selectedKind,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = kindMenuOpen, onDismissRequest = { kindMenuOpen = false }) {
                    providerChoices.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.title) },
                            onClick = {
                                selectedKind = kind.name
                                model = kind.defaultModel
                                baseUrl = kind.defaultBaseUrl
                                kindMenuOpen = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.sidekick_model_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(stringResource(R.string.sidekick_base_url_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val kind = runCatching { ProviderKind.valueOf(selectedKind) }.getOrNull() ?: ProviderKind.ANTHROPIC
                    onSaveProvider(
                        ProviderProfile(
                            kind = kind,
                            baseUrl = baseUrl.trim(),
                            model = model.trim().ifBlank { kind.defaultModel },
                        ),
                    )
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sidekick_save)) }
            if (saved) {
                Text(stringResource(R.string.sidekick_saved), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sidekick_fallback_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.sidekick_fallback_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = fallbackEnabled, onCheckedChange = onSetFallbackEnabled)
        }

        if (fallbackEnabled) {
            OutlinedTextField(
                value = fallbackModel,
                onValueChange = onSetFallbackModel,
                label = { Text(stringResource(R.string.sidekick_fallback_model_label)) },
                placeholder = { Text(stringResource(R.string.sidekick_fallback_model_hint), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.sidekick_fallback_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.local_model_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.local_model_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = localModelEnabled, onCheckedChange = onSetLocalModelEnabled)
        }

        if (localModelEnabled) {
            OutlinedTextField(
                value = localModelBaseUrl,
                onValueChange = onSetLocalModelBaseUrl,
                label = { Text(stringResource(R.string.local_model_base_url_label)) },
                placeholder = { Text(stringResource(R.string.local_model_base_url_hint), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = localModelName,
                onValueChange = onSetLocalModelName,
                label = { Text(stringResource(R.string.local_model_name_label)) },
                placeholder = { Text(stringResource(R.string.local_model_name_hint), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.local_model_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sidekick_background_research), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.sidekick_background_research_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = backgroundResearch, onCheckedChange = onSetBackgroundResearch)
        }

        if (backgroundResearch) {
            OutlinedTextField(
                value = researchSources,
                onValueChange = onSetResearchSources,
                label = { Text(stringResource(R.string.sidekick_research_sources)) },
                placeholder = { Text(stringResource(R.string.sidekick_research_sources_hint), fontSize = 12.sp) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.sidekick_research_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Eco Mode (Token Saver) toggle. Opt-in and off by default; it only reduces costs that scale
 * with verbosity and polling, never disables a feature the user explicitly started.
 */
@Composable
fun EcoModeSettings(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.eco_mode_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.eco_mode_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }

        Text(
            stringResource(R.string.eco_mode_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * MCP Servers management: add, edit, test ("Test & List Tools"), enable/disable and delete
 * servers that the Sidekick assistant may use for tool calls. Everything is opt-in; a server is
 * only contacted when the user presses "Test" or when the assistant actually invokes a tool.
 */
@Composable
fun McpServersSettings(
    servers: List<McpServerConfig>,
    toolCatalog: Map<String, List<McpTool>>,
    testingServerId: String?,
    onSave: (McpServerConfig) -> Unit,
    onDelete: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
) {
    var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingServer = servers.firstOrNull { it.id == editingServerId }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (servers.isEmpty()) {
            Text(
                stringResource(R.string.mcp_servers_empty),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            servers.forEach { server ->
                McpServerRow(
                    server = server,
                    tools = toolCatalog[server.id].orEmpty(),
                    testing = testingServerId == server.id,
                    onToggle = { onSetEnabled(server.id, it) },
                    onEdit = { editingServerId = server.id },
                    onDelete = { onDelete(server.id) },
                    onTest = { onTest(server.id) },
                )
            }
        }

        OutlinedButton(
            onClick = { editingServerId = "__new__" },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Build, null, Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.mcp_add_server))
        }

        Text(
            stringResource(R.string.mcp_servers_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (editingServerId != null) {
        McpServerEditor(
            server = editingServer,
            onDismiss = { editingServerId = null },
            onSave = { config ->
                onSave(config)
                editingServerId = null
            },
            onDelete = editingServer?.let { existing ->
                {
                    onDelete(existing.id)
                    editingServerId = null
                }
            },
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServerConfig,
    tools: List<McpTool>,
    testing: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (server.transport == McpTransport.HTTP) server.url else server.command,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = server.enabled, onCheckedChange = onToggle)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onTest, enabled = server.enabled && !testing) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.mcp_test_list_tools), fontSize = 12.sp)
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, stringResource(R.string.mcp_edit_server), Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, stringResource(R.string.mcp_delete_server), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            if (tools.isNotEmpty()) {
                Text(
                    tools.joinToString(" · ") { it.name },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun McpServerEditor(
    server: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(server?.name.orEmpty()) }
    var transport by rememberSaveable { mutableStateOf((server?.transport ?: McpTransport.HTTP).name) }
    var url by rememberSaveable { mutableStateOf(server?.url.orEmpty()) }
    var command by rememberSaveable { mutableStateOf(server?.command.orEmpty()) }
    var token by rememberSaveable { mutableStateOf(server?.bearerToken.orEmpty()) }
    var confirmMode by rememberSaveable { mutableStateOf((server?.confirmMode ?: McpToolConfirmMode.MUTATING_ONLY).name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (server == null) R.string.mcp_add_server else R.string.mcp_edit_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.mcp_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.mcp_transport_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { transport = McpTransport.HTTP.name }, modifier = Modifier.weight(1f)) {
                        Text("HTTP", color = if (transport == McpTransport.HTTP.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { transport = McpTransport.STDIO.name }, modifier = Modifier.weight(1f)) {
                        Text("Stdio", color = if (transport == McpTransport.STDIO.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (transport == McpTransport.HTTP.name) {
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.mcp_url_label)) }, placeholder = { Text("https://…", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.mcp_command_label)) }, placeholder = { Text(stringResource(R.string.mcp_command_hint), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text(stringResource(R.string.mcp_token_label)) },
                    placeholder = { Text(stringResource(R.string.mcp_token_hint), fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.mcp_confirm_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    McpToolConfirmMode.entries.forEach { mode ->
                        TextButton(
                            onClick = { confirmMode = mode.name },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                when (mode) {
                                    McpToolConfirmMode.ALWAYS_CONFIRM -> stringResource(R.string.mcp_confirm_always)
                                    McpToolConfirmMode.MUTATING_ONLY -> stringResource(R.string.mcp_confirm_mutating)
                                    McpToolConfirmMode.NEVER -> stringResource(R.string.mcp_confirm_never)
                                },
                                fontSize = 11.sp,
                                color = if (confirmMode == mode.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.mcp_token_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val existingId = server?.id ?: java.util.UUID.randomUUID().toString()
                    onSave(
                        McpServerConfig(
                            id = existingId,
                            name = name.trim(),
                            transport = runCatching { McpTransport.valueOf(transport) }.getOrDefault(McpTransport.HTTP),
                            url = url.trim(),
                            command = command.trim(),
                            bearerToken = token.trim(),
                            confirmMode = runCatching { McpToolConfirmMode.valueOf(confirmMode) }.getOrDefault(McpToolConfirmMode.MUTATING_ONLY),
                            enabled = server?.enabled ?: true,
                        ),
                    )
                },
                enabled = name.isNotBlank() && (if (transport == McpTransport.HTTP.name) url.isNotBlank() else command.isNotBlank()),
            ) {
                Text(stringResource(R.string.mcp_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.mcp_delete_server), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.mcp_cancel)) }
            }
        },
    )
}

/**
 * Document base (RAG) management: the user saves small text documents and the Sidekick assistant
 * retrieves the most relevant passages at question time. Everything is on-device and opt-in; the
 * store is a keyword index (no embedding service, no external call) and is never sent anywhere
 * except the relevant passages injected into the user's own configured provider.
 */
@Composable
fun RagSettings(
    enabled: Boolean,
    documents: List<RagDocument>,
    onSetEnabled: (Boolean) -> Unit,
    onSaveDocument: (RagDocument) -> Unit,
    onDeleteDocument: (String) -> Unit,
) {
    var editingDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingDocument = documents.firstOrNull { it.id == editingDocumentId }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rag_enabled), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.rag_enabled_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }

        if (documents.isEmpty()) {
            Text(
                stringResource(R.string.rag_empty),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            documents.forEach { document ->
                RagDocumentRow(
                    document = document,
                    onEdit = { editingDocumentId = document.id },
                    onDelete = { onDeleteDocument(document.id) },
                )
            }
        }

        OutlinedButton(
            onClick = { editingDocumentId = "__new__" },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.rag_add_document))
        }

        Text(
            stringResource(R.string.rag_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (editingDocumentId != null) {
        RagDocumentEditor(
            document = editingDocument,
            onDismiss = { editingDocumentId = null },
            onSave = { document ->
                onSaveDocument(document)
                editingDocumentId = null
            },
        )
    }
}

@Composable
private fun RagDocumentRow(
    document: RagDocument,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Description, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    document.title.ifBlank { stringResource(R.string.rag_untitled) },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val charCount = stringResource(R.string.rag_char_count, document.text.length)
                Text(
                    if (document.source.isNotBlank()) "$charCount · ${document.source}" else charCount,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, stringResource(R.string.rag_edit_document), Modifier.size(17.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, stringResource(R.string.rag_delete_document), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RagDocumentEditor(
    document: RagDocument?,
    onDismiss: () -> Unit,
    onSave: (RagDocument) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(document?.title.orEmpty()) }
    var source by rememberSaveable { mutableStateOf(document?.source.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(document?.text.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (document == null) R.string.rag_add_document else R.string.rag_edit_document)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text(stringResource(R.string.rag_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    source,
                    { source = it },
                    label = { Text(stringResource(R.string.rag_source_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    text,
                    { text = it.take(RagIndex.MAX_DOC_TEXT_CHARS) },
                    label = { Text(stringResource(R.string.rag_text_label)) },
                    placeholder = { Text(stringResource(R.string.rag_text_hint), fontSize = 12.sp) },
                    minLines = 4,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.rag_char_count, text.length),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        RagDocument(
                            id = document?.id ?: java.util.UUID.randomUUID().toString(),
                            title = title.trim().ifBlank { text.trim().take(42).ifBlank { "Document" } },
                            text = text.trim(),
                            source = source.trim(),
                        ),
                    )
                },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.rag_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rag_cancel)) }
        },
    )
}


/**
 * SSH Servers management: add/edit/delete profiles, choose password or private-key auth, run a
 * TOFU-gated "Test connection", toggle the SSH tunnel, and run a single remote command. Secrets
 * are passed up through [onSave] and never displayed after entry; the pinned host-key fingerprint
 * is confirmed through a dedicated trust dialog on first use.
 */
@Composable
fun SshSettings(
    profiles: List<SshProfile>,
    testingProfileId: String?,
    runningTunnelIds: Set<String>,
    pendingTofu: SshTofuPrompt?,
    commandResult: SshCommandResult?,
    onSave: (SshProfile, String?, String?, String?) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (String) -> Unit,
    onToggleTunnel: (String, Boolean) -> Unit,
    onConfirmTofu: () -> Unit,
    onDismissTofu: () -> Unit,
    onResetFingerprint: (String) -> Unit,
    onRunCommand: (String, String) -> Unit,
    onDismissCommandResult: () -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = profiles.firstOrNull { it.id == editingProfileId }
    var runningCommandFor by rememberSaveable { mutableStateOf<String?>(null) }
    var commandText by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (profiles.isEmpty()) {
            Text(
                stringResource(R.string.ssh_empty),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            profiles.forEach { profile ->
                SshProfileRow(
                    profile = profile,
                    testing = testingProfileId == profile.id,
                    tunnelRunning = profile.id in runningTunnelIds,
                    onToggleTunnel = { onToggleTunnel(profile.id, it) },
                    onEdit = { editingProfileId = profile.id },
                    onDelete = { onDelete(profile.id) },
                    onTest = { onTest(profile.id) },
                    onRunCommand = { runningCommandFor = profile.id },
                    onOpenTerminal = { onOpenTerminal(profile.id) },
                )
            }
        }

        OutlinedButton(
            onClick = { editingProfileId = "__new__" },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.ssh_add_server))
        }

        Text(
            stringResource(R.string.ssh_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (editingProfileId != null) {
        SshProfileEditor(
            profile = editingProfile,
            onDismiss = { editingProfileId = null },
            onSave = { profile, password, key, passphrase ->
                onSave(profile, password, key, passphrase)
                editingProfileId = null
            },
            onDelete = editingProfile?.let { existing ->
                {
                    onDelete(existing.id)
                    editingProfileId = null
                }
            },
            onResetFingerprint = editingProfile?.let { existing ->
                { onResetFingerprint(existing.id) }
            },
        )
    }

    pendingTofu?.let { prompt ->
        SshTofuDialog(prompt = prompt, onConfirm = onConfirmTofu, onDismiss = onDismissTofu)
    }

    runningCommandFor?.let { profileId ->
        val profile = profiles.firstOrNull { it.id == profileId }
        if (profile != null) {
            SshRunCommandDialog(
                profile = profile,
                command = commandText,
                onCommandChange = { commandText = it },
                result = commandResult?.takeIf { it.profileId == profile.id },
                onRun = { onRunCommand(profile.id, commandText) },
                onDismiss = {
                    runningCommandFor = null
                    commandText = ""
                    onDismissCommandResult()
                },
            )
        }
    }
}

@Composable
private fun SshProfileRow(
    profile: SshProfile,
    testing: Boolean,
    tunnelRunning: Boolean,
    onToggleTunnel: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onRunCommand: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "${profile.username}@${profile.endpointLabel()} · " +
                            stringResource(if (profile.authType == SshAuthType.PASSWORD) R.string.ssh_auth_password else R.string.ssh_auth_key),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (profile.tunnelEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (tunnelRunning) {
                            stringResource(R.string.ssh_tunnel_running) + ":${profile.tunnelLocalPort}"
                        } else {
                            "127.0.0.1:${profile.tunnelLocalPort} → ${profile.tunnelRemoteHost}:${profile.tunnelRemotePort}"
                        },
                        fontSize = 11.sp,
                        color = if (tunnelRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Switch(checked = tunnelRunning, onCheckedChange = onToggleTunnel)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onTest, enabled = !testing) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ssh_test_connection), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onRunCommand) {
                    Icon(Icons.Default.Build, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ssh_run_command), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onOpenTerminal) {
                    Icon(Icons.Default.Terminal, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ssh_open_terminal), fontSize = 12.sp)
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, stringResource(R.string.ssh_edit_server), Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, stringResource(R.string.ssh_delete_server), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SshProfileEditor(
    profile: SshProfile?,
    onDismiss: () -> Unit,
    onSave: (SshProfile, String?, String?, String?) -> Unit,
    onDelete: (() -> Unit)?,
    onResetFingerprint: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(profile?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((profile?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(profile?.username ?: "root") }
    var authType by rememberSaveable { mutableStateOf((profile?.authType ?: SshAuthType.PASSWORD).name) }
    var password by rememberSaveable { mutableStateOf("") }
    var privateKey by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var passphraseRequired by rememberSaveable { mutableStateOf(profile?.passphraseRequired ?: false) }
    var tunnelEnabled by rememberSaveable { mutableStateOf(profile?.tunnelEnabled ?: false) }
    var localPort by rememberSaveable { mutableStateOf((profile?.tunnelLocalPort ?: 11434).toString()) }
    var remoteHost by rememberSaveable { mutableStateOf(profile?.tunnelRemoteHost ?: "127.0.0.1") }
    var remotePort by rememberSaveable { mutableStateOf((profile?.tunnelRemotePort ?: 11434).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (profile == null) R.string.ssh_add_server else R.string.ssh_edit_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ssh_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.ssh_host_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.ssh_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.ssh_username_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text(stringResource(R.string.ssh_auth_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { authType = SshAuthType.PASSWORD.name }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ssh_auth_password), color = if (authType == SshAuthType.PASSWORD.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { authType = SshAuthType.PRIVATE_KEY.name }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ssh_auth_key), color = if (authType == SshAuthType.PRIVATE_KEY.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (authType == SshAuthType.PASSWORD.name) {
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text(stringResource(R.string.ssh_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        privateKey,
                        { privateKey = it },
                        label = { Text(stringResource(R.string.ssh_private_key_label)) },
                        placeholder = { Text(stringResource(R.string.ssh_private_key_hint), fontSize = 12.sp) },
                        minLines = 4,
                        maxLines = 9,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = passphraseRequired, onCheckedChange = { passphraseRequired = it })
                        Text(stringResource(R.string.ssh_passphrase_required), fontSize = 12.sp)
                    }
                    if (passphraseRequired) {
                        OutlinedTextField(
                            passphrase,
                            { passphrase = it },
                            label = { Text(stringResource(R.string.ssh_passphrase_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ssh_tunnel_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = tunnelEnabled, onCheckedChange = { tunnelEnabled = it })
                }
                if (tunnelEnabled) {
                    OutlinedTextField(
                        localPort,
                        { localPort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.ssh_tunnel_local_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(remoteHost, { remoteHost = it }, label = { Text(stringResource(R.string.ssh_tunnel_remote_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        remotePort,
                        { remotePort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.ssh_tunnel_remote_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val existingId = profile?.id ?: java.util.UUID.randomUUID().toString()
                    val auth = runCatching { SshAuthType.valueOf(authType) }.getOrDefault(SshAuthType.PASSWORD)
                    onSave(
                        SshProfile(
                            id = existingId,
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                            username = username.trim().ifBlank { "root" },
                            authType = auth,
                            passphraseRequired = passphraseRequired,
                            tunnelEnabled = tunnelEnabled,
                            tunnelLocalPort = localPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 11434,
                            tunnelRemoteHost = remoteHost.trim().ifBlank { "127.0.0.1" },
                            tunnelRemotePort = remotePort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 11434,
                        ),
                        password?.trim()?.takeIf(String::isNotEmpty),
                        privateKey?.trim()?.takeIf(String::isNotEmpty),
                        passphrase?.trim()?.takeIf(String::isNotEmpty),
                    )
                },
                enabled = name.isNotBlank() && host.isNotBlank() && username.isNotBlank(),
            ) {
                Text(stringResource(R.string.ssh_save))
            }
        },
        dismissButton = {
            Row {
                if (onResetFingerprint != null) {
                    TextButton(onClick = onResetFingerprint) {
                        Text(stringResource(R.string.ssh_reset_pin))
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.ssh_delete_server), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ssh_cancel)) }
            }
        },
    )
}

@Composable
private fun SshTofuDialog(
    prompt: SshTofuPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_tofu_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ssh_tofu_message, prompt.profileName, prompt.fingerprint), fontSize = 13.sp)
                Text(
                    prompt.fingerprint,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ssh_tofu_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ssh_tofu_cancel)) }
        },
    )
}

@Composable
private fun SshRunCommandDialog(
    profile: SshProfile,
    command: String,
    onCommandChange: (String) -> Unit,
    result: SshCommandResult?,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_run_command) + " · ${profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    command,
                    onCommandChange,
                    label = { Text(stringResource(R.string.ssh_run_command)) },
                    placeholder = { Text(stringResource(R.string.ssh_command_hint), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (result != null) {
                    Text(stringResource(R.string.ssh_command_result), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        result.exitStatus?.let { stringResource(R.string.ssh_command_exit, it) } ?: "",
                        fontSize = 11.sp,
                        color = if (result.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        result.output.ifBlank { stringResource(R.string.ssh_command_no_output) },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRun, enabled = command.isNotBlank()) { Text(stringResource(R.string.ssh_run_command)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ssh_cancel)) }
        },
    )
}
