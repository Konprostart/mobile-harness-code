package com.mobileharness.code.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileharness.code.R
import com.mobileharness.code.network.ssh.SshProfile
import com.mobileharness.code.terminal.TerminalCell
import com.mobileharness.code.terminal.TerminalColor
import com.mobileharness.code.terminal.TerminalKey
import com.mobileharness.code.terminal.TerminalKeyMapper
import com.mobileharness.code.terminal.TerminalModifier
import kotlinx.coroutines.withTimeoutOrNull

private val TerminalDefaultFg = Color(0xFFE6EDF3)
private val TerminalDefaultBg = Color(0xFF0D1117)
private val ModifierOneShot = Color(0xFFD29922)
private val ModifierLocked = Color(0xFF3FB950)

private const val MinFontSizeSp = 8f
private const val MaxFontSizeSp = 28f

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

/** Maps a hardware key to a semantic terminal key (Enter/Backspace are left to the text field). */
private fun hardwareTerminalKey(key: Key): TerminalKey? = when (key) {
    Key.Tab -> TerminalKey.TAB
    Key.Escape -> TerminalKey.ESC
    Key.Delete -> TerminalKey.DELETE
    Key.Insert -> TerminalKey.INSERT
    Key.DirectionUp -> TerminalKey.ARROW_UP
    Key.DirectionDown -> TerminalKey.ARROW_DOWN
    Key.DirectionLeft -> TerminalKey.ARROW_LEFT
    Key.DirectionRight -> TerminalKey.ARROW_RIGHT
    Key.MoveHome -> TerminalKey.HOME
    Key.MoveEnd -> TerminalKey.END
    Key.PageUp -> TerminalKey.PAGE_UP
    Key.PageDown -> TerminalKey.PAGE_DOWN
    Key.F1 -> TerminalKey.F1
    Key.F2 -> TerminalKey.F2
    Key.F3 -> TerminalKey.F3
    Key.F4 -> TerminalKey.F4
    Key.F5 -> TerminalKey.F5
    Key.F6 -> TerminalKey.F6
    Key.F7 -> TerminalKey.F7
    Key.F8 -> TerminalKey.F8
    Key.F9 -> TerminalKey.F9
    Key.F10 -> TerminalKey.F10
    Key.F11 -> TerminalKey.F11
    Key.F12 -> TerminalKey.F12
    else -> null
}

private fun keyToLetterChar(key: Key): Char? = when (key) {
    Key.A -> 'a'; Key.B -> 'b'; Key.C -> 'c'; Key.D -> 'd'; Key.E -> 'e'; Key.F -> 'f'
    Key.G -> 'g'; Key.H -> 'h'; Key.I -> 'i'; Key.J -> 'j'; Key.K -> 'k'; Key.L -> 'l'
    Key.M -> 'm'; Key.N -> 'n'; Key.O -> 'o'; Key.P -> 'p'; Key.Q -> 'q'; Key.R -> 'r'
    Key.S -> 's'; Key.T -> 't'; Key.U -> 'u'; Key.V -> 'v'; Key.W -> 'w'; Key.X -> 'x'
    Key.Y -> 'y'; Key.Z -> 'z'
    else -> null
}

private fun modifierAccent(state: TerminalModifier): Color? = when (state) {
    TerminalModifier.OFF -> null
    TerminalModifier.ONE_SHOT -> ModifierOneShot
    TerminalModifier.LOCKED -> ModifierLocked
}

private fun modifierLabel(base: String, state: TerminalModifier): String = when (state) {
    TerminalModifier.OFF -> base
    TerminalModifier.ONE_SHOT -> "$base ⁺"
    TerminalModifier.LOCKED -> "$base ●"
}

/**
 * Full-screen interactive SSH terminal (PTY + VT100/ANSI). It owns the remote shell lifecycle
 * through [MainViewModel] and renders the emulated grid as a virtualized list of monospace lines
 * with a dark, high-contrast background. It adds a two-row "extra keys" terminal keyboard
 * (with sticky/lock Ctrl+Alt modifiers and auto-repeat), hardware-keyboard support, clipboard
 * copy/paste and pinch-to-zoom font scaling.
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
    var fontSize by rememberSaveable { mutableStateOf(12f) }
    var fnMode by rememberSaveable { mutableStateOf(false) }
    // Sticky/lock modifier toggles survive rotation (enum is Serializable, so the default saver
    // restores them without losing the user's one-shot/locked Ctrl+Alt state).
    var ctrlState by rememberSaveable { mutableStateOf(TerminalModifier.OFF) }
    var altState by rememberSaveable { mutableStateOf(TerminalModifier.OFF) }

    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }

    val lineHeight = (fontSize * 1.2f).sp
    val measurer = rememberTextMeasurer()
    val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = lineHeight)
    val cellWidth = remember(fontSize) { measurer.measure(AnnotatedString("M"), monoStyle).size.width.toFloat().coerceAtLeast(1f) }
    val cellHeight = remember(fontSize) { measurer.measure(AnnotatedString("M"), monoStyle).size.height.toFloat().coerceAtLeast(1f) }

    val ctrlActive = TerminalKeyMapper.isModifierActive(ctrlState)
    val altActive = TerminalKeyMapper.isModifierActive(altState)

    val sendKey: (TerminalKey) -> Unit = { key ->
        viewModel.sendSshTerminalInput(TerminalKeyMapper.sequence(key, ctrlActive, altActive))
        ctrlState = TerminalKeyMapper.consumeOneShot(ctrlState)
        altState = TerminalKeyMapper.consumeOneShot(altState)
    }
    val sendChar: (Char) -> Unit = { ch ->
        viewModel.sendSshTerminalInput(TerminalKeyMapper.charSequence(ch, ctrlActive, altActive))
        ctrlState = TerminalKeyMapper.consumeOneShot(ctrlState)
        altState = TerminalKeyMapper.consumeOneShot(altState)
    }

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

    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        if (ctrl && shift) {
            when (event.key) {
                Key.V -> { viewModel.pasteSshTerminal(); return true }
                Key.C -> { viewModel.copySshTerminal(); return true }
                else -> Unit
            }
        }

        hardwareTerminalKey(event.key)?.let { key ->
            viewModel.sendSshTerminalInput(TerminalKeyMapper.sequence(key, ctrl, alt))
            return true
        }

        keyToLetterChar(event.key)?.let { letter ->
            if (ctrl || alt) {
                val ch = if (shift && !ctrl) letter.uppercaseChar() else letter
                viewModel.sendSshTerminalInput(TerminalKeyMapper.charSequence(ch, ctrl, alt))
                return true
            }
        }

        return false
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
                .imePadding()
                .onPreviewKeyEvent { handleHardwareKey(it) },
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
                    .pointerInput(Unit) {
                        // Two-finger pinch only; a single-finger drag is left to the LazyColumn
                        // scroll below, so zoom never steals scrolling.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (!zoom.isNaN() && zoom > 0f && zoom != 1f) {
                                        fontSize = (fontSize * zoom).coerceIn(MinFontSizeSp, MaxFontSizeSp)
                                        pressed.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { viewModel.pasteSshTerminal() })
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
                                fontSize = fontSize.sp,
                                lineHeight = lineHeight,
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }

            // Extra keys — a compact two-row terminal keyboard (Fn toggles the function row).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SshTerminalKeyButton(
                    label = if (fnMode) "Fn ✓" else "Fn",
                    accent = if (fnMode) ModifierLocked else null,
                    onClick = { fnMode = !fnMode },
                )
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (fnMode) {
                        SshTerminalKeyButton("F1") { sendKey(TerminalKey.F1) }
                        SshTerminalKeyButton("F2") { sendKey(TerminalKey.F2) }
                        SshTerminalKeyButton("F3") { sendKey(TerminalKey.F3) }
                        SshTerminalKeyButton("F4") { sendKey(TerminalKey.F4) }
                        SshTerminalKeyButton("F5") { sendKey(TerminalKey.F5) }
                        SshTerminalKeyButton("F6") { sendKey(TerminalKey.F6) }
                        SshTerminalKeyButton("F7") { sendKey(TerminalKey.F7) }
                        SshTerminalKeyButton("F8") { sendKey(TerminalKey.F8) }
                        SshTerminalKeyButton("F9") { sendKey(TerminalKey.F9) }
                        SshTerminalKeyButton("F10") { sendKey(TerminalKey.F10) }
                        SshTerminalKeyButton("F11") { sendKey(TerminalKey.F11) }
                        SshTerminalKeyButton("F12") { sendKey(TerminalKey.F12) }
                        SshTerminalKeyButton("Ins") { sendKey(TerminalKey.INSERT) }
                        SshTerminalKeyButton("Del", repeatable = true) { sendKey(TerminalKey.DELETE) }
                        SshTerminalKeyButton("|") { sendChar('|') }
                        SshTerminalKeyButton("~") { sendChar('~') }
                        SshTerminalKeyButton("\\") { sendChar('\\') }
                        SshTerminalKeyButton("{") { sendChar('{') }
                        SshTerminalKeyButton("}") { sendChar('}') }
                        SshTerminalKeyButton("[") { sendChar('[') }
                        SshTerminalKeyButton("]") { sendChar(']') }
                    } else {
                        SshTerminalKeyButton("Esc") { sendKey(TerminalKey.ESC) }
                        SshTerminalKeyButton(
                            label = modifierLabel("Ctrl", ctrlState),
                            accent = modifierAccent(ctrlState),
                            onClick = { ctrlState = TerminalKeyMapper.nextModifier(ctrlState) },
                        )
                        SshTerminalKeyButton(
                            label = modifierLabel("Alt", altState),
                            accent = modifierAccent(altState),
                            onClick = { altState = TerminalKeyMapper.nextModifier(altState) },
                        )
                        SshTerminalKeyButton("Tab") { sendKey(TerminalKey.TAB) }
                        SshTerminalKeyButton("Home") { sendKey(TerminalKey.HOME) }
                        SshTerminalKeyButton("End") { sendKey(TerminalKey.END) }
                        SshTerminalKeyButton("PgUp") { sendKey(TerminalKey.PAGE_UP) }
                        SshTerminalKeyButton("PgDn") { sendKey(TerminalKey.PAGE_DOWN) }
                        SshTerminalKeyButton("←", repeatable = true) { sendKey(TerminalKey.ARROW_LEFT) }
                        SshTerminalKeyButton("↓", repeatable = true) { sendKey(TerminalKey.ARROW_DOWN) }
                        SshTerminalKeyButton("↑", repeatable = true) { sendKey(TerminalKey.ARROW_UP) }
                        SshTerminalKeyButton("→", repeatable = true) { sendKey(TerminalKey.ARROW_RIGHT) }
                        SshTerminalKeyButton("/") { sendChar('/') }
                        SshTerminalKeyButton("-") { sendChar('-') }
                        SshTerminalKeyButton("Ctrl+C") {
                            viewModel.sendSshTerminalInput(TerminalKeyMapper.charSequence('c', ctrl = true))
                        }
                    }
                }
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

/**
 * A compact Material-3 terminal key. [accent] (nullable) paints the border/label/background for
 * highlighted states (one-shot / locked modifiers, active Fn). [repeatable] enables auto-repeat
 * while the key is held (arrows, Delete, …): a quick tap fires once, a hold repeats at ~55 ms.
 */
@Composable
private fun SshTerminalKeyButton(
    label: String,
    repeatable: Boolean = false,
    accent: Color? = null,
    onClick: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val shape = RoundedCornerShape(6.dp)
    val borderColor = accent ?: Color(0xFF30363D)
    val bg = if (accent != null) accent.copy(alpha = 0.18f) else Color(0xFF161B22)
    val press = if (repeatable) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    currentOnClick()
                    var delayMillis = 450L
                    while (true) {
                        when (val released = withTimeoutOrNull(delayMillis) { tryAwaitRelease() }) {
                            null -> { currentOnClick(); delayMillis = 55L }
                            true -> break
                            false -> break // cancelled (e.g. parent scroll took over)
                        }
                    }
                },
            )
        }
    } else {
        Modifier.clickable(onClick = currentOnClick)
    }
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = accent ?: TerminalDefaultFg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp),
        )
    }
}
