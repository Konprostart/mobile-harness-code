package com.mobileharness.code.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mobileharness.code.R

/**
 * Thin, lifecycle-aware wrapper around the system [SpeechRecognizer] for the Sidekick input bar.
 * It surfaces live partial results and the final result through callbacks and reports a listening
 * flag through Compose snapshot state so the microphone button can show its active state.
 */
class SpeechRecognizerState(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext

    var isListening by mutableStateOf(false)
        private set

    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null

    fun start(languageTag: String) {
        stopInternal()
        val speech = SpeechRecognizer.createSpeechRecognizer(appContext)
        if (speech == null) {
            onError?.invoke(appContext.getString(R.string.sidekick_mic_error))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (!languageTag.isBlank() && !languageTag.equals("auto", ignoreCase = true)) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                // NO_MATCH and SPEECH_TIMEOUT are benign (user said nothing); surface the rest.
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    val resId = if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        R.string.sidekick_mic_permission
                    } else {
                        R.string.sidekick_mic_error
                    }
                    onError?.invoke(appContext.getString(resId))
                }
                finish()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onFinalResult?.invoke(text)
                finish()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onPartialResult?.invoke(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer = speech
        isListening = true
        runCatching { speech.startListening(intent) }.onFailure {
            isListening = false
            onError?.invoke(appContext.getString(R.string.sidekick_mic_error))
        }
    }

    fun stop() = finish()

    override fun close() {
        stopInternal()
    }

    /**
     * Ends the current recognition and releases the recognizer. Called from the listener's
     * terminal callbacks ([onResults]/[onError]) and from [stop]; reuses [stopInternal] so the
     * recognizer is stopped, destroyed and detached in one place instead of being held open after
     * the user has finished speaking.
     */
    private fun finish() {
        stopInternal()
    }

    private fun stopInternal() {
        val current = recognizer ?: return
        recognizer = null
        isListening = false
        runCatching { current.stopListening() }
        runCatching { current.destroy() }
    }
}

@Composable
fun rememberSpeechRecognizerState(context: Context): SpeechRecognizerState {
    val state = remember(context) { SpeechRecognizerState(context) }
    // The microphone must not stay live while the app is in the background: as soon as the
    // Activity is stopped (screen off, switching apps, recents) the recognizer is released so no
    // audio is captured and no recognizer survives a background → foreground cycle. Split-screen /
    // PiP keep the Activity started, so an intentional listen continues exactly as the user sees it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.stop() }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}
