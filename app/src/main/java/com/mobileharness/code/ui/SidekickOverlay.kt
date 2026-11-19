package com.mobileharness.code.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobileharness.code.R

/**
 * Global, discreet floating AI button that opens the Sidekick assistant without leaving the
 * current screen or project. Rendered above the whole app surface when the user enables it in
 * Settings → Assistant; hidden otherwise.
 *
 * The button can be dragged vertically — a plain tap still opens the assistant, while a drag
 * after the touch slop moves it up/down. Its normalized vertical offset (0f = top, 1f = bottom)
 * is persisted through [onFabOffsetChange] and re-clamped to the safe area (status/navigation
 * bars) on every recomposition and rotation.
 */
@Composable
fun SidekickFloatingOverlay(
    visible: Boolean,
    fabOffset: Float = 0.5f,
    onFabOffsetChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    var showDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Keep the button out of the status and navigation bars, with a small extra margin.
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val topMargin = insets.calculateTopPadding() + 12.dp
        val bottomMargin = insets.calculateBottomPadding() + 12.dp
        val fabSize = 56.dp
        val minY = topMargin
        val maxY = (maxHeight - fabSize - bottomMargin).coerceAtLeast(minY)

        var dragging by remember { mutableStateOf(false) }
        // Single stable state object: the button's live top offset in Dp.
        var y by remember { mutableStateOf(minY + (maxY - minY) * fabOffset.coerceIn(0f, 1f)) }

        // Re-sync from the persisted offset when it or the safe area changes externally
        // (e.g. rotation, backup restore). Never fights an active drag.
        LaunchedEffect(fabOffset, minY, maxY) {
            if (!dragging) {
                y = minY + (maxY - minY) * fabOffset.coerceIn(0f, 1f)
            }
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = y, end = 10.dp)
                .pointerInput(minY, maxY) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            val range = (maxY - minY).value
                            val normalized = if (range <= 0f) 0.5f
                            else ((y - minY).value / range).coerceIn(0f, 1f)
                            onFabOffsetChange(normalized)
                        },
                        onDragCancel = {
                            dragging = false
                            val range = (maxY - minY).value
                            val normalized = if (range <= 0f) 0.5f
                            else ((y - minY).value / range).coerceIn(0f, 1f)
                            onFabOffsetChange(normalized)
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        val delta = with(density) { dragAmount.y.toDp() }
                        y = (y + delta).coerceIn(minY, maxY)
                    }
                },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = stringResource(R.string.sidekick_fab_label),
                modifier = Modifier.size(22.dp),
            )
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                SidekickScreen(onClose = { showDialog = false })
            }
        }
    }
}
