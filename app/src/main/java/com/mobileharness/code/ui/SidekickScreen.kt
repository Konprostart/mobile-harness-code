package com.mobileharness.code.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mobileharness.code.R
import com.mobileharness.code.data.AppPreferences
import com.mobileharness.code.sidekick.SidekickAttachmentIO
import com.mobileharness.code.sidekick.SidekickEngine
import com.mobileharness.code.sidekick.SidekickFile
import com.mobileharness.code.sidekick.SidekickImage
import com.mobileharness.code.sidekick.SidekickInput
import com.mobileharness.code.sidekick.SidekickResult
import com.mobileharness.code.sidekick.SpeechLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SidekickMessage(
    val fromUser: Boolean,
    val text: String,
    val reasoning: String? = null,
    val reasoningExpanded: MutableState<Boolean> = mutableStateOf(false),
)

private enum class SidekickMode { ASK, PROMPT, RESEARCH }

private sealed interface PendingAttachment {
    data class Image(val data: SidekickImage) : PendingAttachment
    data class File(val data: SidekickFile) : PendingAttachment
}

/**
 * In-app "Sidekick" assistant: a lightweight chat that answers questions about the app, helps
 * improve prompts, or researches the user's allowed sources. It uses the provider the user
 * configured for their coding agent — or an independent, more powerful model when opted in.
 * The input bar also supports voice typing and, in Ask mode, image/file attachments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidekickScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val resolver = remember { context.contentResolver }
    val engine = remember { SidekickEngine(context) }
    val preferences = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<SidekickMessage>() }
    val attachments = remember { mutableStateListOf<PendingAttachment>() }
    var input by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(SidekickMode.ASK) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Voice input. The language choice is persisted so an explicit pick (e.g. Serbian) survives
    // screen reopen/app restart instead of silently resetting to "auto".
    val speech = rememberSpeechRecognizerState(context)
    var languageChoice by remember { mutableStateOf(preferences.sidekickLanguage) }
    val systemLocales = remember {
        val config = context.resources.configuration
        val locales = config.locales
        (0 until locales.size()).map { locales[it].toLanguageTag() }
    }
    val resolvedLanguage = remember(languageChoice) { SpeechLanguage.resolve(languageChoice, systemLocales) }

    LaunchedEffect(speech) {
        speech.onPartialResult = { partial -> input = partial }
        speech.onFinalResult = { final -> input = final }
        speech.onError = { msg -> errorMessage = msg }
    }

    val greeting = stringResource(R.string.sidekick_greeting)
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) messages.add(SidekickMessage(fromUser = false, text = greeting))
    }

    val placeholder = when (mode) {
        SidekickMode.ASK -> stringResource(R.string.sidekick_placeholder)
        SidekickMode.PROMPT -> stringResource(R.string.sidekick_prompt_placeholder)
        SidekickMode.RESEARCH -> stringResource(R.string.sidekick_research_placeholder)
    }
    val modeNote = when (mode) {
        SidekickMode.ASK -> stringResource(R.string.sidekick_note)
        SidekickMode.PROMPT -> stringResource(R.string.sidekick_prompt_note)
        SidekickMode.RESEARCH -> stringResource(R.string.sidekick_research_note)
    }

    fun addCapturedImage(bitmap: Bitmap, name: String) {
        scope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    val base64 = SidekickAttachmentIO.bitmapToBase64(bitmap)
                    if (base64.length > SidekickInput.MAX_IMAGE_BASE64_CHARS) error("too large")
                    SidekickImage(name, "image/jpeg", base64)
                }.onFailure { bitmap.recycle() }.getOrNull()
            }
            if (image != null) {
                attachments.add(PendingAttachment.Image(image))
                errorMessage = null
            } else {
                errorMessage = context.getString(R.string.sidekick_attachment_too_large)
            }
        }
    }

    fun addImageUri(uri: Uri) {
        scope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    val (name, _) = SidekickAttachmentIO.metadata(resolver, uri)
                    val bitmap = SidekickAttachmentIO.decodeImage(resolver, uri)
                        ?: error("decode failed")
                    try {
                        val base64 = SidekickAttachmentIO.bitmapToBase64(bitmap)
                        if (base64.length > SidekickInput.MAX_IMAGE_BASE64_CHARS) error("too large")
                        SidekickImage(name, "image/jpeg", base64)
                    } finally {
                        bitmap.recycle()
                    }
                }.getOrNull()
            }
            if (image != null) {
                attachments.add(PendingAttachment.Image(image))
                errorMessage = null
            } else {
                errorMessage = context.getString(R.string.sidekick_attachment_too_large)
            }
        }
    }

    fun addFileUri(uri: Uri) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val (name, mime) = SidekickAttachmentIO.metadata(resolver, uri)
                if (!SidekickInput.isTextFile(name, mime)) {
                    return@withContext AddFileOutcome.Unsupported
                }
                val text = SidekickAttachmentIO.readTextFile(
                    resolver, uri, SidekickInput.MAX_FILE_TEXT_CHARS + 64 * 1024,
                )
                if (text.isBlank()) AddFileOutcome.Failed else AddFileOutcome.Success(SidekickFile(name, mime, text))
            }
            when (outcome) {
                is AddFileOutcome.Success -> {
                    attachments.add(PendingAttachment.File(outcome.file))
                    errorMessage = null
                }
                AddFileOutcome.Unsupported -> errorMessage = context.getString(R.string.sidekick_attachment_unsupported)
                AddFileOutcome.Failed -> errorMessage = context.getString(R.string.sidekick_attachment_failed)
            }
        }
    }

    fun send() {
        val question = input.trim()
        if (loading) return
        if (question.isEmpty() && attachments.isEmpty()) return
        val images = attachments.filterIsInstance<PendingAttachment.Image>().map { it.data }
        val files = attachments.filterIsInstance<PendingAttachment.File>().map { it.data }
        messages.add(SidekickMessage(fromUser = true, text = displayText(question, attachments)))
        attachments.clear()
        input = ""
        errorMessage = null
        loading = true
        scope.launch {
            val answer: SidekickResult = when (mode) {
                SidekickMode.ASK -> engine.ask(question, images, files)
                SidekickMode.PROMPT -> engine.optimizePrompt(question.ifBlank { "Improve this prompt." })
                SidekickMode.RESEARCH -> engine.research(question)
            }
            messages.add(SidekickMessage(fromUser = false, text = answer.text, reasoning = answer.reasoning))
            loading = false
        }
    }

    fun switchMode(next: SidekickMode) {
        if (mode != next && next != SidekickMode.ASK) attachments.clear()
        mode = next
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            speech.start(SpeechLanguage.resolve(languageChoice, systemLocales))
        } else {
            errorMessage = context.getString(R.string.sidekick_mic_permission)
        }
    }
    val takePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) addCapturedImage(bitmap, "photo.jpg")
    }
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) addImageUri(uri) }
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) addFileUri(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sidekick_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidekickModeButton(SidekickMode.ASK, mode, stringResource(R.string.sidekick_mode_ask)) { switchMode(SidekickMode.ASK) }
                SidekickModeButton(SidekickMode.PROMPT, mode, stringResource(R.string.sidekick_mode_prompt)) { switchMode(SidekickMode.PROMPT) }
                SidekickModeButton(SidekickMode.RESEARCH, mode, stringResource(R.string.sidekick_mode_research)) { switchMode(SidekickMode.RESEARCH) }
            }
            Text(
                modeNote,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (errorMessage != null) {
                Text(
                    errorMessage.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start,
                        ) {
                            if (message.reasoning != null) {
                                ReasoningBox(message.reasoning, message.reasoningExpanded)
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (message.fromUser) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    message.text,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    fontSize = 14.sp,
                                    color = if (message.fromUser) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                if (loading) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.sidekick_loading), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (attachments.isNotEmpty()) {
                AttachmentChipsRow(attachments, onRemove = { attachments.remove(it) })
            }

            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(placeholder, fontSize = 13.sp) },
                        singleLine = true,
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(2.dp))
                    if (mode == SidekickMode.ASK) {
                        AttachmentMenu(
                            onPhoto = { takePhotoLauncher.launch(null) },
                            onImage = {
                                pickImageLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onFile = { pickFileLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                    MicButton(
                        isListening = speech.isListening,
                        resolvedLanguage = resolvedLanguage,
                        languageChoice = languageChoice,
                        onLanguageChoice = {
                            languageChoice = it
                            preferences.sidekickLanguage = it
                        },
                        onToggle = {
                            if (speech.isListening) {
                                speech.stop()
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                speech.start(resolvedLanguage)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                    IconButton(onClick = ::send, enabled = (input.isNotBlank() || attachments.isNotEmpty()) && !loading) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            stringResource(R.string.sidekick_send),
                            tint = if ((input.isNotBlank() || attachments.isNotEmpty()) && !loading) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface AddFileOutcome {
    data class Success(val file: SidekickFile) : AddFileOutcome
    object Unsupported : AddFileOutcome
    object Failed : AddFileOutcome
}

private fun displayText(question: String, attachments: List<PendingAttachment>): String {
    val parts = buildList {
        if (question.isNotBlank()) add(question)
        attachments.forEach { attachment ->
            when (attachment) {
                is PendingAttachment.Image -> add("\uD83D\uDDBC ${attachment.data.displayName}")
                is PendingAttachment.File -> add("\uD83D\uDCC4 ${attachment.data.displayName}")
            }
        }
    }
    return parts.joinToString("\n").ifBlank { "…" }
}

@Composable
private fun AttachmentChipsRow(
    attachments: List<PendingAttachment>,
    onRemove: (PendingAttachment) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments) { attachment ->
            val icon = when (attachment) {
                is PendingAttachment.Image -> Icons.Default.Image
                is PendingAttachment.File -> Icons.Default.Description
            }
            val name = when (attachment) {
                is PendingAttachment.Image -> attachment.data.displayName
                is PendingAttachment.File -> attachment.data.displayName
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(name, fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.sidekick_remove_attachment), Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentMenu(onPhoto: () -> Unit, onImage: () -> Unit, onFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.AttachFile, stringResource(R.string.sidekick_attach), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sidekick_attach_photo)) },
                leadingIcon = { Icon(Icons.Default.AddAPhoto, null) },
                onClick = { open = false; onPhoto() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sidekick_attach_image)) },
                leadingIcon = { Icon(Icons.Default.Image, null) },
                onClick = { open = false; onImage() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sidekick_attach_file)) },
                leadingIcon = { Icon(Icons.Default.Description, null) },
                onClick = { open = false; onFile() },
            )
        }
    }
}

@Composable
private fun MicButton(
    isListening: Boolean,
    resolvedLanguage: String,
    languageChoice: String,
    onLanguageChoice: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val languages = listOf(
        SpeechLanguage.AUTO to stringResource(R.string.sidekick_speech_auto),
        "sr-RS" to "Srpski",
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "sv-SE" to "Svenska",
    )
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle) {
            Icon(
                if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                stringResource(if (isListening) R.string.sidekick_mic_stop else R.string.sidekick_mic),
                tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Language, stringResource(R.string.sidekick_speech_language), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                languages.forEach { (tag, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onLanguageChoice(tag); open = false },
                        trailingIcon = { if (tag == languageChoice) Icon(Icons.Default.AutoAwesome, null, Modifier.size(14.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningBox(reasoning: String, expanded: MutableState<Boolean>) {
    val isExpanded = expanded.value
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded.value = !expanded.value }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sidekick_reasoning_title),
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    stringResource(if (isExpanded) R.string.sidekick_hide_reasoning else R.string.sidekick_show_reasoning),
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isExpanded) {
                Text(
                    reasoning,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowScope.SidekickModeButton(
    value: SidekickMode,
    selected: SidekickMode,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            label,
            maxLines = 1,
            color = if (selected == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected == value) androidx.compose.ui.text.font.FontWeight.SemiBold
            else androidx.compose.ui.text.font.FontWeight.Normal,
        )
    }
}
