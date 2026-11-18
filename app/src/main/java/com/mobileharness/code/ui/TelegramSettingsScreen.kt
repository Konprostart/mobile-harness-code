package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.R
import com.mobileharness.code.telegram.TelegramBotService
import com.mobileharness.code.telegram.TelegramSettings

/**
 * Opt-in Telegram bot settings. The bot token is validated and stored encrypted through the
 * ApiKeyVault (never shown back), pairing uses a single-use 6-digit code, and the poller runs
 * only while enabled. Off by default with no telemetry.
 */
@Composable
fun TelegramSettingsSection() {
    val context = LocalContext.current
    val settings = remember { TelegramSettings(context) }
    val clipboard = LocalClipboardManager.current

    var enabled by remember { mutableStateOf(settings.enabled) }
    var token by rememberSaveable { mutableStateOf("") }
    var tokenConfigured by remember { mutableStateOf(settings.hasToken()) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    fun show(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    // Pre-resolve every string used inside plain (non-composable) onClick/onCheckedChange lambdas.
    val strEnabledStarted = stringResource(R.string.telegram_enabled_started)
    val strTokenRequired = stringResource(R.string.telegram_token_required)
    val strDisabledStopped = stringResource(R.string.telegram_disabled_stopped)
    val strTokenInvalid = stringResource(R.string.telegram_token_invalid)
    val strSaved = stringResource(R.string.telegram_saved)
    val strTokenCleared = stringResource(R.string.telegram_token_cleared)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.telegram_enabled), fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) stringResource(R.string.telegram_running_status)
                    else stringResource(R.string.telegram_stopped_status),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { value ->
                    enabled = value
                    settings.setEnabled(value)
                    if (value) {
                        if (settings.hasToken()) {
                            TelegramBotService.start(context)
                            show(strEnabledStarted)
                        } else {
                            show(strTokenRequired, error = true)
                        }
                    } else {
                        TelegramBotService.stop(context)
                        show(strDisabledStopped)
                    }
                },
            )
        }

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = {
                Text(
                    if (tokenConfigured) stringResource(R.string.telegram_token_saved)
                    else stringResource(R.string.telegram_token),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!settings.saveToken(token)) {
                        show(strTokenInvalid, error = true)
                    } else {
                        tokenConfigured = true
                        token = ""
                        show(strSaved)
                        if (enabled) TelegramBotService.start(context)
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.telegram_save_token)) }
            if (tokenConfigured) {
                OutlinedButton(onClick = {
                    settings.clearToken()
                    tokenConfigured = false
                    TelegramBotService.stop(context)
                    enabled = false
                    show(strTokenCleared)
                }) { Text(stringResource(R.string.telegram_clear_token)) }
            }
        }

        if (tokenConfigured) {
            Button(
                onClick = { pairingCode = settings.generatePairingCode() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.telegram_generate_pair_code)) }

            val code = pairingCode
            if (code != null) {
                val text = stringResource(R.string.telegram_pair_code_hint, code)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.telegram_pair_code_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(code, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString("/pair $code")) }) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.telegram_copy_pair_command))
                    }
                }
                Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        message?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            stringResource(R.string.telegram_opt_in_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
