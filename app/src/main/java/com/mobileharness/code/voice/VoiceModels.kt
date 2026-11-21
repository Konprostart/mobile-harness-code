package com.mobileharness.code.voice

/**
 * Voice (STT/TTS) configuration model. Providers are HTTP services, not bundled SDKs; the app only
 * needs the endpoint, key, model and voice the user picks. `DEVICE` STT maps to the Android OS
 * SpeechRecognizer (no key) and is kept as the always-available local option.
 */
enum class VoiceProviderKind { OPENAI, ELEVENLABS, DEEPGRAM, CARTESIA, ASSEMBLYAI, CUSTOM, DEVICE }

enum class VoiceCapability { STT, TTS }

/** A single configured voice endpoint. */
data class VoiceProfile(
    val kind: VoiceProviderKind,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
    val language: String = "",
    val capability: VoiceCapability = VoiceCapability.STT,
)

/** Provider family → sensible default model/labels for the UI. */
object VoiceProviders {
    val defaultModels = mapOf(
        VoiceProviderKind.OPENAI to "whisper-1",
        VoiceProviderKind.ELEVENLABS to "eleven_multilingual_v2",
        VoiceProviderKind.DEEPGRAM to "nova-2",
        VoiceProviderKind.CARTESIA to "sonic-2",
        VoiceProviderKind.ASSEMBLYAI to "universal-1",
        VoiceProviderKind.CUSTOM to "",
        VoiceProviderKind.DEVICE to "",
    )

    val defaultBaseUrls = mapOf(
        VoiceProviderKind.OPENAI to "https://api.openai.com/v1",
        VoiceProviderKind.ELEVENLABS to "https://api.elevenlabs.io/v1",
        VoiceProviderKind.DEEPGRAM to "https://api.deepgram.com/v1",
        VoiceProviderKind.CARTESIA to "https://api.cartesia.ai",
        VoiceProviderKind.ASSEMBLYAI to "https://api.assemblyai.com/v2",
    )

    fun defaultBaseUrl(kind: VoiceProviderKind): String = defaultBaseUrls[kind].orEmpty()
    fun defaultModel(kind: VoiceProviderKind): String = defaultModels[kind].orEmpty()
}
