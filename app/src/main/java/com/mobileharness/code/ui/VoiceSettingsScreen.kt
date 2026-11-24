package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.mobileharness.code.voice.LiveModeAccess
import com.mobileharness.code.voice.TtsPlayer
import com.mobileharness.code.voice.VoiceEngine
import com.mobileharness.code.voice.VoiceProviderKind
import com.mobileharness.code.voice.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val sttKinds = listOf(
    VoiceProviderKind.DEVICE, VoiceProviderKind.OPENAI, VoiceProviderKind.GROQ,
    VoiceProviderKind.DEEPGRAM, VoiceProviderKind.ASSEMBLYAI, VoiceProviderKind.ELEVENLABS,
    VoiceProviderKind.CUSTOM,
)
private val ttsKinds = listOf(
    VoiceProviderKind.EDGE, VoiceProviderKind.OPENAI, VoiceProviderKind.ELEVENLABS,
    VoiceProviderKind.DEEPGRAM, VoiceProviderKind.CARTESIA, VoiceProviderKind.CUSTOM,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    state: AppUiState,
    onClose: () -> Unit,
    onSaveSettings: (VoiceSettings) -> Unit,
    getApiKey: (VoiceProviderKind) -> String?,
    setApiKey: (VoiceProviderKind, String) -> Unit,
    onStartTask: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { VoiceEngine(context) }
    val player = remember { TtsPlayer(context) }
    var settings = state.voiceSettings

    var apiKeyInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var showLiveMode by remember { mutableStateOf(false) }

    fun persist(next: VoiceSettings) {
        settings = next
        onSaveSettings(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item { SectionHeader("Speech to text (STT)") }
            item {
                ProviderDropdown(
                    label = "STT provider",
                    kinds = sttKinds,
                    selected = settings.sttKind,
                    onSelect = { persist(settings.copy(sttKind = it)) },
                )
            }
            if (settings.sttKind != VoiceProviderKind.DEVICE) {
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    OutlinedTextField(
                        value = settings.sttModel,
                        onValueChange = { persist(settings.copy(sttModel = it.trim())) },
                        label = { Text("STT model") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OutlinedTextField(
                    value = settings.voiceLanguage,
                    onValueChange = { persist(settings.copy(voiceLanguage = it.trim())) },
                    label = { Text("Language (auto, sr-RS, en-US, …)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item { Spacer(Modifier.height(20.dp)); SectionHeader("Text to speech (TTS)") }
            item {
                ProviderDropdown(
                    label = "TTS provider",
                    kinds = ttsKinds,
                    selected = settings.ttsKind,
                    onSelect = { persist(settings.copy(ttsKind = it)) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OutlinedTextField(
                    value = settings.ttsModel,
                    onValueChange = { persist(settings.copy(ttsModel = it.trim())) },
                    label = { Text("TTS model (blank = default)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OutlinedTextField(
                    value = settings.ttsVoice,
                    onValueChange = { persist(settings.copy(ttsVoice = it.trim())) },
                    label = { Text("Voice") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Speed", Modifier.width(90.dp))
                    Slider(
                        value = settings.speed,
                        onValueChange = { persist(settings.withSpeed(it)) },
                        valueRange = 0.5f..2f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("%.1fx".format(settings.speed), Modifier.width(44.dp), fontSize = 12.sp)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Auto-read replies aloud", Modifier.weight(1f))
                    Switch(checked = settings.autoRead, onCheckedChange = { persist(settings.copy(autoRead = it)) })
                }
            }

            if (settings.sttKind != VoiceProviderKind.DEVICE || settings.ttsKind != VoiceProviderKind.EDGE) {
                item { Spacer(Modifier.height(20.dp)); SectionHeader("API key") }
                item {
                    val needsKey = settings.sttKind != VoiceProviderKind.DEVICE && settings.sttKind != VoiceProviderKind.EDGE
                    val ttsNeedsKey = settings.ttsKind != VoiceProviderKind.EDGE
                    val relevant = if (needsKey || ttsNeedsKey) {
                        if (needsKey) settings.sttKind else settings.ttsKind
                    } else null
                    if (relevant != null) {
                        val saved = getApiKey(relevant).orEmpty()
                        OutlinedTextField(
                            value = apiKeyInput.ifBlank { if (saved.isBlank()) "" else "••••" },
                            onValueChange = { apiKeyInput = it },
                            label = { Text("API key for ${relevant.name}") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            val k = apiKeyInput.trim()
                            if (k.isNotEmpty()) setApiKey(relevant, k)
                            apiKeyInput = ""
                            message = if (k.isNotEmpty()) "Key saved." else "Key unchanged."
                        }) { Text("Save key") }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)); SectionHeader("Live Mode") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable Live Mode", Modifier.weight(1f))
                    Switch(checked = settings.liveModeEnabled, onCheckedChange = { persist(settings.copy(liveModeEnabled = it)) })
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                ProviderDropdown(
                    label = "Access",
                    kinds = LiveModeAccess.entries.map { it.name },
                    selected = settings.liveModeAccess.name,
                    onSelect = { name -> persist(settings.copy(liveModeAccess = LiveModeAccess.valueOf(name))) },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Speak replies in Live Mode", Modifier.weight(1f))
                    Switch(checked = settings.speakReplies, onCheckedChange = { persist(settings.copy(speakReplies = it)) })
                }
            }
            item {
                Button(
                    enabled = settings.liveModeEnabled,
                    onClick = { showLiveMode = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Start Live Mode") }
            }

            item { Spacer(Modifier.height(20.dp)); SectionHeader("Test") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !testing, onClick = {
                        scope.launch {
                            testing = true
                            message = null
                            val kind = settings.ttsKind
                            val key = getApiKey(kind).orEmpty()
                            message = withContext(Dispatchers.IO) {
                                VoiceEngine(context).validateKey(kind, key).fold(
                                    onSuccess = { "Connection OK (${kind.name})." },
                                    onFailure = { "Connection failed: ${it.message}" },
                                )
                            }
                            testing = false
                        }
                    }) { Text("Test connection") }
                    OutlinedButton(enabled = !testing, onClick = {
                        scope.launch {
                            testing = true
                            message = "Speaking…"
                            val text = "Ovo je test govorne sinteze. This is a text to speech test."
                            val bytes = withContext(Dispatchers.IO) { engine.synthesize(text) }
                            bytes.onSuccess {
                                withContext(Dispatchers.Main) { player.play(it) }
                                message = "Playing test speech."
                            }.onFailure { message = "TTS failed: ${it.message}" }
                            testing = false
                        }
                    }) { Text("Test playback") }
                }
            }
            if (message != null) {
                item { Spacer(Modifier.height(10.dp)); Text(message.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (showLiveMode) {
        LiveModeOverlay(
            access = settings.liveModeAccess,
            speakReplies = settings.speakReplies,
            onStartTask = onStartTask,
            onClose = { showLiveMode = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ProviderDropdown(label: String, kinds: List<VoiceProviderKind>, selected: VoiceProviderKind, onSelect: (VoiceProviderKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            kinds.forEach { kind ->
                DropdownMenuItem(text = { Text(kind.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { onSelect(kind); expanded = false })
            }
        }
    }
}

@Composable
private fun ProviderDropdown(label: String, kinds: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            kinds.forEach { kind ->
                DropdownMenuItem(text = { Text(kind.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { onSelect(kind); expanded = false })
            }
        }
    }
}
