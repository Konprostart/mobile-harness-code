package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.sidekick.SidekickEngine
import com.mobileharness.code.sidekick.SpeechLanguage
import com.mobileharness.code.voice.LiveExchange
import com.mobileharness.code.voice.LiveMode
import com.mobileharness.code.voice.LiveModeAccess
import com.mobileharness.code.voice.LivePhase
import com.mobileharness.code.voice.SpeechText
import com.mobileharness.code.voice.TtsPlayer
import com.mobileharness.code.voice.TtsTextChunker
import com.mobileharness.code.voice.VoiceCommand
import com.mobileharness.code.voice.VoiceCommandKind
import com.mobileharness.code.voice.VoiceCommandParser
import com.mobileharness.code.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hands-free Live Mode controller: reuses the existing system [SpeechRecognizerState] for listening,
 * [SidekickEngine] for the conversation, and [TtsPlayer]/[VoiceEngine] for spoken replies. The loop
 * is listen → transcribe → answer → speak → listen until the user stops it.
 */
class LiveModeController(
    context: android.content.Context,
    private val scope: CoroutineScope,
    private val access: LiveModeAccess,
    private val speakReplies: Boolean,
    private val onStartTask: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val sidekick = SidekickEngine(appContext)
    private val voiceEngine = VoiceEngine(appContext)
    private val player = TtsPlayer(appContext)
    val speech = SpeechRecognizerState(appContext)

    var phase by mutableStateOf(LivePhase.STARTING)
    val exchanges = mutableStateListOf<LiveExchange>()
    var asking by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var pendingCommand by mutableStateOf<VoiceCommand?>(null)

    private var closed = false
    private var languageTag = "auto"
    private var lastReply = ""

    fun start(language: String) {
        languageTag = language
        listen()
    }

    fun pause() {
        phase = LivePhase.PAUSED
        speech.stop()
        player.stop()
    }

    fun resume() = listen()

    fun interrupt() = if (phase == LivePhase.LISTENING) pause() else resume()

    fun stop() {
        closed = true
        speech.close()
        player.stop()
        phase = LivePhase.PAUSED
    }

    private fun listen() {
        if (closed) return
        phase = LivePhase.LISTENING
        speech.onFinalResult = { text -> handleUtterance(text) }
        speech.onError = { msg -> error = msg; listen() }
        speech.start(languageTag)
    }

    private fun handleUtterance(text: String) {
        val command = VoiceCommandParser.parse(text)
        when {
            command.kind == VoiceCommandKind.NONE -> answer(text)
            command.requiresConfirmation -> pendingCommand = command
            else -> applySafeCommand(command)
        }
    }

    fun confirmCommand() {
        val cmd = pendingCommand ?: return
        pendingCommand = null
        when (cmd.kind) {
            VoiceCommandKind.STOP_AGENT -> stop()
            VoiceCommandKind.RUN_TESTS -> {
                onStartTask("Run the project's test suite and report the results.")
                listen()
            }
            VoiceCommandKind.OPEN_PROJECT -> listen()
            VoiceCommandKind.CHANGE_AGENT -> listen()
            else -> listen()
        }
    }

    fun dismissCommand() {
        pendingCommand = null
        listen()
    }

    private fun applySafeCommand(command: VoiceCommand) {
        when (command.kind) {
            VoiceCommandKind.STOP_SPEECH -> { player.stop(); listen() }
            VoiceCommandKind.PAUSE -> pause()
            VoiceCommandKind.RESUME -> resume()
            VoiceCommandKind.REPEAT, VoiceCommandKind.READ_REPLY -> {
                speakThenListen(lastReply)
            }
            else -> listen()
        }
    }

    private fun answer(text: String) {
        if (closed) return
        asking = text
        error = null
        phase = LivePhase.THINKING
        scope.launch {
            val result = sidekick.liveAsk(access, exchanges.toList(), text)
            val reply = result.text.trim()
            exchanges += LiveExchange(text, reply)
            lastReply = reply
            asking = null
            if (access == LiveModeAccess.START_TASKS) {
                val brief = LiveMode.briefFrom(reply)
                if (brief != null) onStartTask(brief)
            }
            speakThenListen(reply)
        }
    }

    private fun speakThenListen(reply: String) {
        val speakable = SpeechText.forSpeech(reply)
        if (speakReplies && SpeechText.isSpeakable(speakable)) {
            phase = LivePhase.SPEAKING
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { voiceEngine.synthesize(speakable.take(500)) }
                bytes.onSuccess {
                    player.onFinished = { if (!closed && phase != LivePhase.PAUSED) listen() }
                    player.play(it)
                }.onFailure {
                    error = "TTS failed: ${it.message}"
                    if (!closed && phase != LivePhase.PAUSED) listen()
                }
            }
        } else {
            if (!closed && phase != LivePhase.PAUSED) listen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveModeOverlay(
    access: LiveModeAccess,
    speakReplies: Boolean,
    onStartTask: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(access, speakReplies) {
        LiveModeController(context, scope, access, speakReplies, onStartTask)
    }
    val systemLocales = remember {
        val config = context.resources.configuration
        (0 until config.locales.size()).map { config.locales[it].toLanguageTag() }
    }
    val resolved = remember { SpeechLanguage.resolve("auto", systemLocales) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        controller.start(resolved)
    }
    androidx.compose.runtime.DisposableEffect(controller) {
        onDispose { controller.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Mode · ${access.name}") },
                navigationIcon = {
                    IconButton(onClick = { controller.stop(); onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { controller.interrupt() },
                shape = CircleShape,
                containerColor = when (controller.phase) {
                    LivePhase.LISTENING -> MaterialTheme.colorScheme.primary
                    LivePhase.THINKING -> MaterialTheme.colorScheme.tertiary
                    LivePhase.SPEAKING -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Icon(if (controller.phase == LivePhase.PAUSED) Icons.Default.Mic else Icons.Default.Pause, null)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(controller.phase.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { controller.stop(); onClose() }) { Icon(Icons.Default.Stop, "Stop") }
            }
            if (controller.error != null) {
                Text(controller.error.orEmpty(), Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(controller.exchanges) { exchange ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        if (exchange.user.isNotBlank()) {
                            Text("You", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(exchange.user, fontSize = 14.sp)
                        }
                        if (exchange.reply.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Assistant", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            Text(exchange.reply, fontSize = 14.sp)
                        }
                    }
                }
                if (controller.asking != null) {
                    item { Text("Listening: ${controller.asking}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }

    controller.pendingCommand?.let { command ->
        AlertDialog(
            onDismissRequest = { controller.dismissCommand() },
            title = { Text("Confirm voice command") },
            text = { Text("Run \"${command.kind.name}\"? This can affect real work.") },
            confirmButton = { TextButton(onClick = { controller.confirmCommand() }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { controller.dismissCommand() }) { Text("Cancel") } },
        )
    }
}
