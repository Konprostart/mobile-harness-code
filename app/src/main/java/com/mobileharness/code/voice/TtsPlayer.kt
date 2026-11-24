package com.mobileharness.code.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/**
 * Minimal TTS audio playback over [MediaPlayer]. Synthesized bytes are written to a private cache
 * file and played back with pause/resume/stop. Audio focus is requested for the duration of a
 * playback so an incoming call can interrupt it; the listener is a no-op that simply keeps focus
 * request/abandon symmetrical.
 */
class TtsPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    var onFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    fun play(bytes: ByteArray, extension: String = "mp3") {
        stop()
        val file = File(appContext.cacheDir, "tts_${System.nanoTime()}.$extension")
        runCatching { file.writeBytes(bytes) }
        if (file.length() == 0L) {
            file.delete()
            onError?.invoke("TTS produced empty audio")
            return
        }
        currentFile = file
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stopInternal(); onFinished?.invoke() }
            mp.setOnErrorListener { _, _, _ -> stopInternal(); onError?.invoke("Playback failed"); true }
            mp.prepare()
            requestFocus()
            mp.start()
            player = mp
            isPlaying = true
        } catch (e: Exception) {
            runCatching { mp.release() }
            file.delete()
            onError?.invoke(e.message ?: "Playback failed")
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        isPlaying = false
    }

    fun resume() {
        runCatching {
            player?.start()
            isPlaying = true
        }
    }

    fun stop() = stopInternal()

    fun close() = stopInternal()

    private fun stopInternal() {
        val p = player
        player = null
        isPlaying = false
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        currentFile?.let { runCatching { it.delete() } }
        currentFile = null
        abandonFocus()
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                am.requestAudioFocus(
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .build(),
                        )
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        runCatching {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
