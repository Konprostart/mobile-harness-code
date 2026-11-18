package com.mobileharness.code.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
