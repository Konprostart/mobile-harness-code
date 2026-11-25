package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.R
import com.mobileharness.code.ui.theme.PocketOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Small, unobtrusive copy action shown under a single chat message. Copies only [text] and
 * flips to a checkmark with a brief "Copied" confirmation for a couple of seconds.
 */
@Composable
fun MessageCopyButton(text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (copied) {
            Text(
                text = stringResource(R.string.message_copied),
                fontSize = 11.sp,
                color = tint,
            )
            Spacer(Modifier.width(2.dp))
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
                scope.launch {
                    delay(2000)
                    copied = false
                }
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.message_copy),
                tint = if (copied) PocketOrange else tint,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
