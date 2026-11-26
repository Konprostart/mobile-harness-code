package com.mobileharness.code.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.R
import com.mobileharness.code.cloud.ConnectionKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Callbacks for the automatic signing-key cloud backup UI. */
data class SigningCloudBackupActions(
    val setEnabled: (Boolean) -> Unit,
    val setProvider: (String) -> Unit,
    val setConnection: (String) -> Unit,
    val setKeepVersions: (Int) -> Unit,
    val setPassphrase: (String) -> Unit,
    val connect: () -> Unit,
    val backupNow: () -> Unit,
    val restore: () -> Unit,
)

private const val MIN_PASSPHRASE = 8

/**
 * Automatic signing-key cloud backup. One optional ON/OFF, a pluggable storage provider, an
 * encrypted `.sgnbak` passphrase, versioning and restore. The engine only ever uploads the
 * passphrase-encrypted container — never raw keystores or passwords.
 */
@Composable
fun SigningCloudBackupSettings(
    state: SigningCloudUiState,
    actions: SigningCloudBackupActions,
) {
    val config = state.config
    var passphraseVisible by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseDirty by remember { mutableStateOf(false) }
    var keepVersions by remember { mutableStateOf(config.keepVersions.toString()) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            actions.setConnection(uri.toString())
            actions.connect()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Master switch.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.signing_cloud_enabled), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.signing_cloud_enabled_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = config.enabled, onCheckedChange = actions.setEnabled)
        }

        if (config.enabled) {
            // Provider selection.
            Text(stringResource(R.string.signing_cloud_provider), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            state.providers.forEach { provider ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = config.providerId == provider.id,
                        onClick = { actions.setProvider(provider.id) },
                    )
                    Text(provider.displayName, fontSize = 13.sp)
                }
            }

            // Connection.
            val provider = state.providers.firstOrNull { it.id == config.providerId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        if (provider?.connectionKind == ConnectionKind.PICK_FOLDER) folderLauncher.launch(null)
                        else actions.connect()
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Cloud, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (provider?.connectionKind == ConnectionKind.PICK_FOLDER) R.string.signing_cloud_pick_folder else R.string.signing_cloud_connect))
                }
                if (config.connection.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (state.connected) Icons.Default.CheckCircle else Icons.Default.Cloud,
                        null,
                        Modifier.size(18.dp),
                        tint = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (config.connection.isBlank() && provider?.connectionKind == ConnectionKind.PICK_FOLDER) {
                Text(stringResource(R.string.signing_cloud_connection_missing), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Passphrase.
            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    passphraseDirty = true
                },
                label = { Text(stringResource(R.string.signing_cloud_passphrase)) },
                singleLine = true,
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Icon(
                            if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(R.string.signing_cloud_passphrase),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.signing_cloud_passphrase_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (config.passphraseConfigured) {
                Text(stringResource(R.string.signing_cloud_passphrase_set), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (passphraseDirty) {
                OutlinedButton(
                    onClick = { actions.setPassphrase(passphrase); passphraseDirty = false },
                    enabled = passphrase.isEmpty() || passphrase.length >= MIN_PASSPHRASE,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.signing_cloud_save_passphrase), fontSize = 13.sp)
                }
            }

            // Version retention.
            OutlinedTextField(
                value = keepVersions,
                onValueChange = { input ->
                    val filtered = input.filter(Char::isDigit)
                    keepVersions = filtered
                    filtered.toIntOrNull()?.let { value ->
                        if (value != config.keepVersions) actions.setKeepVersions(value)
                    }
                },
                label = { Text(stringResource(R.string.signing_cloud_keep_versions)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // Status + last backup.
            val lastBackup = if (config.lastBackupAt > 0) formatTimestamp(config.lastBackupAt) else stringResource(R.string.signing_cloud_last_backup_none)
            Text(
                stringResource(R.string.signing_cloud_last_backup) + ": " + lastBackup,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (config.lastBackupKeyCount > 0) {
                Text(
                    stringResource(R.string.signing_cloud_key_count, config.lastBackupKeyCount),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (config.lastBackupError.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.signing_cloud_error, config.lastBackupError), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Actions.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.backupNow, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.signing_cloud_backup_now), maxLines = 1)
                }
                OutlinedButton(onClick = actions.restore, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.signing_cloud_restore), maxLines = 1)
                }
            }

            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.signing_cloud_connect), fontSize = 12.sp)
                }
            }
        }

        state.message?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
