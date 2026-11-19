package com.mobileharness.code.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.BuildConfig
import com.mobileharness.code.R
import com.mobileharness.code.share.QrCode
import com.mobileharness.code.share.ShareLinks
import kotlinx.coroutines.delay

/**
 * Self-contained "Share & Invite" panel: official download link, local QR code,
 * integrity/install guidance, the personal invite code, one-time invite-code
 * redemption and the "Crypto Rewards — SOON" status card. No online QR service,
 * no wallet/RPC/Web3, no telemetry — everything is local and fail-closed.
 */
@Composable
fun ShareInviteSettings(
    inviteCode: String,
    redeemedInviteCode: String,
    inviteShareCount: Int,
    inviteRedeemMessage: String?,
    onRecordShare: () -> Unit,
    onRedeemInvite: (String) -> Unit,
    onClearRedeemMessage: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    var redeemInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(2000)
            copiedLabel = null
        }
    }

    fun copy(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        copiedLabel = label
        onRecordShare()
    }

    fun share() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_send_invite))
            putExtra(Intent.EXTRA_TEXT, ShareLinks.buildShareText(inviteCode, BuildConfig.VERSION_NAME))
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.share_send_invite)),
        )
        onRecordShare()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard {
            Text(
                stringResource(R.string.share_link_label),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                ShareLinks.DOWNLOAD_URL,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(ShareLinks.DOWNLOAD_URL, context.getString(R.string.share_copied)) }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share_copy_link))
                }
                Button(onClick = { share() }) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share_send_invite))
                }
            }
            copiedLabel?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QrCodeImage(ShareLinks.DOWNLOAD_URL, Modifier.size(160.dp))
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.share_qr_label),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        context.getString(R.string.share_version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.share_sha_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard {
            Text(
                stringResource(R.string.share_install_note),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Text(
                stringResource(R.string.invite_my_code),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                inviteCode.ifBlank { stringResource(R.string.invite_code_missing) },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(inviteCode, context.getString(R.string.invite_copied)) }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.invite_copy_code))
                }
            }
            Text(
                stringResource(R.string.invite_status_active),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                context.getString(R.string.invite_share_count_label, inviteShareCount),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Text(
                stringResource(R.string.invite_redeem_title),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            if (redeemedInviteCode.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        context.getString(R.string.invite_status_redeemed, redeemedInviteCode),
                        fontSize = 12.sp,
                    )
                }
            } else {
                OutlinedTextField(
                    value = redeemInput,
                    onValueChange = {
                        redeemInput = it
                        if (inviteRedeemMessage != null) onClearRedeemMessage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.invite_redeem_hint)) },
                )
                Button(
                    onClick = { onRedeemInvite(redeemInput) },
                    enabled = redeemInput.isNotBlank(),
                ) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.invite_redeem_button))
                }
            }
            inviteRedeemMessage?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (redeemedInviteCode.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.crypto_rewards_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    stringResource(R.string.crypto_rewards_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { QrCode.encode(content) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White)
            .padding(6.dp),
    ) {
        if (matrix == null) {
            Text(
                stringResource(R.string.share_qr_error),
                color = Color.Red,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val n = matrix.size
                val cell = size.minDimension / n
                val originX = (size.width - cell * n) / 2f
                val originY = (size.height - cell * n) / 2f
                for (r in 0 until n) {
                    for (c in 0 until n) {
                        if (matrix[r][c]) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(originX + c * cell, originY + r * cell),
                                size = Size(cell, cell),
                            )
                        }
                    }
                }
            }
        }
    }
}
