package com.mobileharness.code.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
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
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.network.ServerSyncConfig
import com.mobileharness.code.network.ServerSyncMode
import com.mobileharness.code.runtime.ReleasePipelineStage

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
