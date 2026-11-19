package com.mobileharness.code.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileharness.code.R
import com.mobileharness.code.network.ssh.SshControlKey
import com.mobileharness.code.network.ssh.SshProfile
import com.mobileharness.code.terminal.TerminalCell
import com.mobileharness.code.terminal.TerminalColor

private val TerminalDefaultFg = Color(0xFFE6EDF3)
private val TerminalDefaultBg = Color(0xFF0D1117)

private val Ansi16 = listOf(
    Color(0xFF000000), Color(0xFFCD3131), Color(0xFF0DBC79), Color(0xFFE5E510),
    Color(0xFF2472C8), Color(0xFFBC3FBC), Color(0xFF11A8CD), Color(0xFFE5E5E5),
    Color(0xFF666666), Color(0xFFF14C4C), Color(0xFF23D18B), Color(0xFFF5F543),
    Color(0xFF3B8EEA), Color(0xFFD670D6), Color(0xFF29B8DB), Color(0xFFFFFFFF),
)

private fun palette256(index: Int): Color = when {
    index < 16 -> Ansi16[index.coerceIn(0, 15)]
    index < 232 -> {
        val i = index - 16
        val level = intArrayOf(0, 95, 135, 175, 215, 255)
        Color(level[i / 36], level[(i % 36) / 6], level[i % 6])
    }
    else -> {
        val v = 8 + (index - 232) * 10
        Color(v, v, v)
    }
}

private fun resolveColor(color: TerminalColor, fallback: Color): Color = when (color) {
    is TerminalColor.Default -> fallback
    is TerminalColor.Palette -> Ansi16[color.index.coerceIn(0, 15)]
    is TerminalColor.Palette256 -> palette256(color.index.coerceIn(0, 255))
    is TerminalColor.Rgb -> Color(color.r, color.g, color.b)
}

/** Renders one terminal row (a list of styled cells) as a color/style AnnotatedString. */
fun renderTerminalLine(cells: List<TerminalCell>): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    while (i < cells.size) {
        val style = cells[i].style
        val fg = resolveColor(style.fg, TerminalDefaultFg)
        val bg = resolveColor(style.bg, TerminalDefaultBg)
        val effFg = if (style.reverse) bg else fg
        val effBg = if (style.reverse) fg else bg
        val span = SpanStyle(
            color = effFg,
            background = effBg,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (style.underline) TextDecoration.Underline else TextDecoration.None,
        )
        var j = i
        while (j < cells.size && cells[j].style == style) j++
        val sb = StringBuilder(j - i)
        for (k in i until j) sb.append(cells[k].char)
        builder.withStyle(span) { append(sb.toString()) }
        i = j
    }
    return builder.toAnnotatedString()
}

/**
 * Full-screen interactive SSH terminal (PTY + VT100/ANSI). It owns the remote shell lifecycle
 * through [MainViewModel] and renders the emulated grid as a virtualized list of monospace lines
 * with a dark, high-contrast background, plus an input bar and quick keys for the control
 * sequences a phone keyboard cannot easily produce.
 */
@Composable
fun SshTerminalScreen(
    profile: SshProfile,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val lines by viewModel.sshTerminalLines.collectAsStateWithLifecycle()
    val connected by viewModel.sshTerminalConnected.collectAsStateWithLifecycle()
    val connecting by viewModel.sshTerminalConnecting.collectAsStateWithLifecycle()
    val error by viewModel.sshTerminalError.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var cols by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val measurer = rememberTextMeasurer()
    val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp)
    val cellWidth = remember { measurer.measure(AnnotatedString("M"), monoStyle).size.width.toFloat().coerceAtLeast(1f) }
    val cellHeight = remember { measurer.measure(AnnotatedString("M"), monoStyle).size.height.toFloat().coerceAtLeast(1f) }

    LaunchedEffect(profile.id) { viewModel.openSshTerminal(profile.id, 80, 24) }
    LaunchedEffect(cols, rows) {
        if (cols > 0 && rows > 0) viewModel.resizeSshTerminal(cols, rows)
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    val sendLine = {
        viewModel.sendSshTerminalInput(input + "\r")
        input = ""
    }

    BackHandler(onBack = {
        viewModel.closeSshTerminal()
        onClose()
    })

    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        containerColor = TerminalDefaultBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    viewModel.closeSshTerminal()
                    onClose()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.ssh_terminal_back), tint = TerminalDefaultFg)
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(0xFF238636).copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF3FB950), modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name.ifBlank { profile.endpointLabel() }, color = TerminalDefaultFg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (connecting) stringResource(R.string.ssh_terminal_connecting)
                        else if (connected) stringResource(R.string.ssh_terminal_connected)
                        else stringResource(R.string.ssh_terminal_disconnected),
                        color = if (connected) Color(0xFF3FB950) else Color(0xFF8B949E),
                        fontSize = 11.sp,
                    )
                }
                if (connected) {
                    IconButton(onClick = {
                        viewModel.closeSshTerminal()
                        onClose()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ssh_terminal_disconnect), tint = TerminalDefaultFg)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .onSizeChanged { size ->
                        val c = (size.width / cellWidth).toInt().coerceAtLeast(1)
                        val r = (size.height / cellHeight).toInt().coerceAtLeast(1)
                        if (c != cols) cols = c
                        if (r != rows) rows = r
                    }
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                    .background(Color(0xFF010409), RoundedCornerShape(8.dp)),
            ) {
                when {
                    connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF3FB950), strokeWidth = 2.dp)
                    error != null && !connected -> Text(
                        error.orEmpty(),
                        color = Color(0xFFF14C4C),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lines) { _, row ->
                            Text(
                                text = renderTerminalLine(row),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }

            // Quick control keys — the soft keyboard cannot produce these.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SshTerminalKeyButton("Ctrl+C") { viewModel.sendSshTerminalControl(SshControlKey.CTRL_C) }
                SshTerminalKeyButton("Ctrl+D") { viewModel.sendSshTerminalControl(SshControlKey.CTRL_D) }
                SshTerminalKeyButton("Esc") { viewModel.sendSshTerminalControl(SshControlKey.ESC) }
                SshTerminalKeyButton("Tab") { viewModel.sendSshTerminalControl(SshControlKey.TAB) }
                SshTerminalKeyButton("↑") { viewModel.sendSshTerminalControl(SshControlKey.ARROW_UP) }
                SshTerminalKeyButton("↓") { viewModel.sendSshTerminalControl(SshControlKey.ARROW_DOWN) }
                SshTerminalKeyButton("←") { viewModel.sendSshTerminalControl(SshControlKey.ARROW_LEFT) }
                SshTerminalKeyButton("→") { viewModel.sendSshTerminalControl(SshControlKey.ARROW_RIGHT) }
            }

            // Input bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocus),
                    placeholder = { Text(stringResource(R.string.ssh_terminal_input_hint), color = Color(0xFF8B949E), fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TerminalDefaultFg),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendLine() }),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3FB950),
                        cursorColor = Color(0xFF3FB950),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = sendLine,
                    shape = CircleShape,
                    color = Color(0xFF238636),
                    contentColor = Color.White,
                ) {
                    Text(
                        stringResource(R.string.ssh_terminal_send),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SshTerminalKeyButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D)),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TerminalDefaultFg, maxLines = 1)
    }
}
