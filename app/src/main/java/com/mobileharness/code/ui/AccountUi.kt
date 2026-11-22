package com.mobileharness.code.ui

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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileharness.code.R
import com.mobileharness.code.backup.BackupController
import com.mobileharness.code.backup.BackupJobStatus
import com.mobileharness.code.backup.BackupOperation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACCOUNT_MIN_PASSWORD = 8
private const val ACCOUNT_MIN_USERNAME = 2

/**
 * The local, password-based account: a named identity whose master password encrypts the
 * portable `.mhbackup` (which now also carries the signing keystore). There is no server —
 * "signing in on a new phone" means creating the account with the same password and restoring
 * the backup file. This screen makes that flow obvious and reminds the user when they last
 * backed up.
 */
@Composable
fun AccountSection(
    state: AppUiState,
    busy: Boolean,
    onCreateAccount: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onClearMessage: () -> Unit,
    onBackedUp: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    val job by BackupController.state.collectAsStateWithLifecycle()

    // When a backup finishes and an account exists, record the timestamp for the reminder.
    LaunchedEffect(job.status, job.operation) {
        if (job.operation == BackupOperation.BACKUP && job.status == BackupJobStatus.COMPLETE) onBackedUp()
    }

    if (showCreate) {
        CreateAccountDialog(
            error = state.accountMessage,
            onDismiss = {
                showCreate = false
                onClearMessage()
            },
            onConfirm = { username, password ->
                showCreate = false
                onCreateAccount(username, password)
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.account_description), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (state.accountUsername.isBlank()) {
            Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AccountCircle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.account_create))
            }
        } else {
            AccountStatusCard(
                username = state.accountUsername,
                lastBackupMillis = state.accountLastBackupMillis,
                onSignOut = onSignOut,
            )
            Text(
                stringResource(R.string.account_backup_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The actual encrypted backup/restore, reused unchanged. A password-protected backup
        // now includes the signing keystore, so restoring it on a new phone brings everything back.
        BackupRestoreSection(busy = busy)
    }
}

@Composable
private fun AccountStatusCard(username: String, lastBackupMillis: Long, onSignOut: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountCircle, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.account_signed_in_as, username), fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(
                    R.string.account_last_backup,
                    if (lastBackupMillis > 0) formatTimestamp(lastBackupMillis) else stringResource(R.string.account_last_backup_never),
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onSignOut) {
                Text(stringResource(R.string.account_sign_out), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CreateAccountDialog(error: String?, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val localError = when {
        username.isNotEmpty() && username.trim().length < ACCOUNT_MIN_USERNAME -> stringResource(R.string.account_username_short)
        password.isNotEmpty() && password.length < ACCOUNT_MIN_PASSWORD -> stringResource(R.string.account_password_short)
        confirm.isNotEmpty() && confirm != password -> stringResource(R.string.account_password_mismatch)
        else -> null
    }
    val message = localError ?: error
    val valid = username.trim().length >= ACCOUNT_MIN_USERNAME && password.length >= ACCOUNT_MIN_PASSWORD && password == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.account_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.account_password)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (visible) R.string.backup_hide_password else R.string.backup_show_password),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.account_password_confirm)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(username.trim(), password) }, enabled = valid) {
                Text(stringResource(R.string.account_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
