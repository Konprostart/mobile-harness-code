package com.mobileharness.code.voice

import android.content.Context
import com.mobileharness.code.data.ApiKeyVault
import com.mobileharness.code.data.AppPreferences
import com.mobileharness.code.token.TokenUsage
import com.mobileharness.code.token.TokenUsageEntry
import com.mobileharness.code.token.TokenUsageEstimator
import com.mobileharness.code.token.TokenUsageStore
import java.io.File

/**
 * Android-side glue between [VoiceGateway], the encrypted [ApiKeyVault] and the Token & Cost
 * Manager. It resolves the user's voice settings plus key, performs STT/TTS, and records an
 * estimated usage entry (voice providers do not meter tokens) so spend shows up in the manager.
 */
class VoiceEngine(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val vault = ApiKeyVault(appContext)
    private val usageStore = TokenUsageStore(File(appContext.filesDir, "token"))

    fun settings(): VoiceSettings = preferences.voiceSettings

    fun voiceKey(kind: VoiceProviderKind): String = vault.get(keyId(kind)).orEmpty()

    fun saveVoiceKey(kind: VoiceProviderKind, key: String) {
        if (key.isBlank()) vault.remove(keyId(kind)) else vault.put(keyId(kind), key.trim())
    }

    /** Validates a provider key (or trivially succeeds for keyless Edge). */
    fun validateKey(kind: VoiceProviderKind, key: String): Result<Unit> =
        VoiceGateway.validateKey(kind, baseUrl(kind), key)

    fun listVoices(kind: VoiceProviderKind, key: String): List<Pair<String, String>> =
        VoiceGateway.listVoices(kind, baseUrl(kind), key).getOrDefault(VoicePresets.voices(kind).map { it to it })

    /** Transcribes audio with the configured STT provider, recording estimated usage. */
    fun transcribe(audioBytes: ByteArray): Result<String> {
        val s = settings()
        if (s.sttKind == VoiceProviderKind.DEVICE) {
            return Result.failure(IllegalStateException("Device STT uses the system recognizer, not the network gateway."))
        }
        val key = voiceKey(s.sttKind)
        if (s.sttKind != VoiceProviderKind.EDGE && key.isBlank()) {
            return Result.failure(IllegalStateException("No API key configured for ${s.sttKind.name}."))
        }
        val started = System.currentTimeMillis()
        return VoiceGateway.transcribe(
            s.sttKind, baseUrl(s.sttKind), key, s.sttModel, languageOrNull(s), audioBytes,
        ).onSuccess { text ->
            recordUsage(s.sttKind, s.sttModel.ifBlank { VoicePresets.byKind(s.sttKind)?.sttModel.orEmpty() }, "voice-stt", TokenUsage(inputTokens = TokenUsageEstimator.estimateTokens(text)), System.currentTimeMillis() - started)
        }
    }

    /** Synthesizes speech with the configured TTS provider, recording estimated usage. */
    fun synthesize(text: String): Result<ByteArray> {
        val s = settings()
        val key = voiceKey(s.ttsKind)
        if (s.ttsKind != VoiceProviderKind.EDGE && key.isBlank()) {
            return Result.failure(IllegalStateException("No API key configured for ${s.ttsKind.name}."))
        }
        val started = System.currentTimeMillis()
        return VoiceGateway.synthesize(
            s.ttsKind, baseUrl(s.ttsKind), key, text, s.ttsModel, s.ttsVoice.ifBlank { VoicePresets.defaultVoice(s.ttsKind, s.ttsModel).orEmpty() }, s.speed,
        ).onSuccess {
            recordUsage(s.ttsKind, s.ttsModel.ifBlank { VoicePresets.byKind(s.ttsKind)?.ttsModel.orEmpty() }, "voice-tts", TokenUsage(outputTokens = TokenUsageEstimator.estimateTokens(text)), System.currentTimeMillis() - started)
        }
    }

    private fun languageOrNull(s: VoiceSettings): String? =
        s.voiceLanguage.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }

    private fun baseUrl(kind: VoiceProviderKind): String = when (kind) {
        VoiceProviderKind.CUSTOM -> "" // user-provided custom endpoint handled by profile, not here
        else -> VoiceGateway.resolvedBaseUrl(kind, "")
    }

    private fun recordUsage(kind: VoiceProviderKind, model: String, agent: String, usage: TokenUsage, latency: Long) {
        usageStore.record(
            TokenUsageEntry(
                timestampMillis = System.currentTimeMillis(),
                providerId = "voice-${kind.name.lowercase()}",
                model = model,
                agent = agent,
                usage = usage,
                tokensEstimated = true,
                latencyMillis = latency,
            ),
        )
    }

    private fun keyId(kind: VoiceProviderKind): String = "voice-" + kind.name.lowercase()
}
