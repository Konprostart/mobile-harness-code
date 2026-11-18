package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.R
import com.mobileharness.code.sidekick.SidekickEngine
import com.mobileharness.code.sidekick.SidekickResult
import kotlinx.coroutines.launch

private data class SidekickMessage(
    val fromUser: Boolean,
    val text: String,
    val reasoning: String? = null,
    val reasoningExpanded: MutableState<Boolean> = mutableStateOf(false),
)

private enum class SidekickMode { ASK, PROMPT, RESEARCH }

/**
 * In-app "Sidekick" assistant: a lightweight chat that answers questions about the app, helps
 * improve prompts, or researches the user's allowed sources. It uses the provider the user
 * configured for their coding agent — or an independent, more powerful model when opted in.
 * No message history is persisted and no key is stored by the assistant itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidekickScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { SidekickEngine(context) }
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<SidekickMessage>() }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(SidekickMode.ASK) }
    val listState = rememberLazyListState()

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

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || loading) return
        messages.add(SidekickMessage(fromUser = true, text = question))
        input = ""
        loading = true
        scope.launch {
            val answer: SidekickResult = when (mode) {
                SidekickMode.ASK -> engine.ask(question)
                SidekickMode.PROMPT -> engine.optimizePrompt(question)
                SidekickMode.RESEARCH -> engine.research(question)
            }
            messages.add(SidekickMessage(fromUser = false, text = answer.text, reasoning = answer.reasoning))
            loading = false
        }
    }

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
                SidekickModeButton(SidekickMode.ASK, mode, stringResource(R.string.sidekick_mode_ask)) { mode = SidekickMode.ASK }
                SidekickModeButton(SidekickMode.PROMPT, mode, stringResource(R.string.sidekick_mode_prompt)) { mode = SidekickMode.PROMPT }
                SidekickModeButton(SidekickMode.RESEARCH, mode, stringResource(R.string.sidekick_mode_research)) { mode = SidekickMode.RESEARCH }
            }
            Text(
                modeNote,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(placeholder, fontSize = 13.sp) },
                        singleLine = true,
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = ::send, enabled = input.isNotBlank() && !loading) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            stringResource(R.string.sidekick_send),
                            tint = if (input.isNotBlank() && !loading) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
