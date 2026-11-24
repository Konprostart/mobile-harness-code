package com.mobileharness.code.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Voice (STT/TTS) configuration model. Providers are HTTP services, not bundled SDKs; the app only
 * needs the endpoint, key, model and voice the user picks. `DEVICE` STT maps to the Android OS
 * SpeechRecognizer (no key) and is kept as the always-available local option — it remains the
 * default until the user explicitly changes it.
 */
enum class VoiceProviderKind {
    OPENAI, GROQ, ELEVENLABS, DEEPGRAM, CARTESIA, ASSEMBLYAI, EDGE, CUSTOM, DEVICE,
}

enum class VoiceCapability { STT, TTS }

/** Wire protocol a voice provider speaks. Voice endpoints are not part of the chat-provider layer. */
enum class VoiceProtocol {
    /** `/audio/transcriptions` (multipart) and `/audio/speech` (JSON), like OpenAI/Groq/local servers. */
    OPENAI_AUDIO,
    ELEVENLABS,
    DEEPGRAM,
    CARTESIA,
    ASSEMBLYAI,
    /** Microsoft Edge Read Aloud: a WebSocket session, no key required. */
    EDGE,
}

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
        VoiceProviderKind.GROQ to "whisper-large-v3",
        VoiceProviderKind.ELEVENLABS to "eleven_multilingual_v2",
        VoiceProviderKind.DEEPGRAM to "nova-3",
        VoiceProviderKind.CARTESIA to "sonic-2",
        VoiceProviderKind.ASSEMBLYAI to "universal",
        VoiceProviderKind.EDGE to "",
        VoiceProviderKind.CUSTOM to "",
        VoiceProviderKind.DEVICE to "",
    )

    val defaultBaseUrls = mapOf(
        VoiceProviderKind.OPENAI to "https://api.openai.com/v1",
        VoiceProviderKind.GROQ to "https://api.groq.com/openai/v1",
        VoiceProviderKind.ELEVENLABS to "https://api.elevenlabs.io/v1",
        VoiceProviderKind.DEEPGRAM to "https://api.deepgram.com/v1",
        VoiceProviderKind.CARTESIA to "https://api.cartesia.ai",
        VoiceProviderKind.ASSEMBLYAI to "https://api.assemblyai.com",
    )

    fun defaultBaseUrl(kind: VoiceProviderKind): String = defaultBaseUrls[kind].orEmpty()
    fun defaultModel(kind: VoiceProviderKind): String = defaultModels[kind].orEmpty()
}

/**
 * One built-in voice provider with the fields a full STT/TTS gateway needs. Keys are NOT stored
 * here — they live in [com.mobileharness.code.data.ApiKeyVault] under a per-provider slot.
 */
data class VoicePreset(
    val kind: VoiceProviderKind,
    val name: String,
    val protocol: VoiceProtocol,
    val baseUrl: String = "",
    val sttModel: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    val supportsStt: Boolean = true,
    val supportsTts: Boolean = true,
    val requiresKey: Boolean = true,
    val keyUrl: String = "",
    val voices: List<String> = emptyList(),
    val sttModels: List<String> = emptyList(),
    val ttsModels: List<String> = emptyList(),
)

/** Built-in providers shipped with the app. Users can override any field or add custom endpoints. */
object VoicePresets {
    val groqSttModels = listOf("whisper-large-v3", "whisper-large-v3-turbo")
    val openAiSttModels = listOf("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe")
    val deepgramSttModels = listOf("nova-3", "nova-2", "enhanced", "base")
    val assemblyAiSttModels = listOf("universal", "slam-1")
    val elevenLabsSttModels = listOf("scribe_v1")

    val groqTtsModels = listOf("playai-tts", "playai-tts-arabic")
    val openAiTtsModels = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd")
    val cartesiaTtsModels = listOf("sonic-3", "sonic-2", "sonic-turbo")
    val deepgramTtsModels = listOf("aura-2-thalia-en", "aura-2-orpheus-en", "aura-asteria-en")
    val elevenLabsTtsModels = listOf("eleven_multilingual_v2", "eleven_turbo_v2_5", "eleven_flash_v2_5")

    val openAiVoices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
    val deepgramVoices = listOf(
        "aura-2-thalia-en", "aura-2-orpheus-en", "aura-2-andromeda-en",
        "aura-asteria-en", "aura-luna-en", "aura-stella-en",
    )

    val all: List<VoicePreset> = listOf(
        VoicePreset(
            kind = VoiceProviderKind.GROQ, name = "Groq", protocol = VoiceProtocol.OPENAI_AUDIO,
            baseUrl = "https://api.groq.com/openai/v1", sttModel = "whisper-large-v3",
            ttsModel = "playai-tts", ttsVoice = "Fritz-PlayAI",
            keyUrl = "https://console.groq.com/keys",
            sttModels = groqSttModels, ttsModels = groqTtsModels,
        ),
        VoicePreset(
            kind = VoiceProviderKind.EDGE, name = "Edge (free)", protocol = VoiceProtocol.EDGE,
            baseUrl = EdgeTtsClient.VOICE_LIST_URL, supportsStt = false, requiresKey = false,
            voices = EdgeVoiceCatalog.fallbackVoices, ttsModels = listOf(""),
        ),
        VoicePreset(
            kind = VoiceProviderKind.OPENAI, name = "OpenAI", protocol = VoiceProtocol.OPENAI_AUDIO,
            baseUrl = "https://api.openai.com/v1", sttModel = "whisper-1",
            ttsModel = "gpt-4o-mini-tts", ttsVoice = "alloy",
            keyUrl = "https://platform.openai.com/api-keys",
            voices = openAiVoices, sttModels = openAiSttModels, ttsModels = openAiTtsModels,
        ),
        VoicePreset(
            kind = VoiceProviderKind.ELEVENLABS, name = "ElevenLabs", protocol = VoiceProtocol.ELEVENLABS,
            baseUrl = "https://api.elevenlabs.io/v1", sttModel = "scribe_v1",
            ttsModel = "eleven_multilingual_v2", ttsVoice = "21m00Tcm4TlvDq8ikWAM",
            keyUrl = "https://elevenlabs.io/app/settings/api-keys",
            sttModels = elevenLabsSttModels, ttsModels = elevenLabsTtsModels,
        ),
        VoicePreset(
            kind = VoiceProviderKind.DEEPGRAM, name = "Deepgram", protocol = VoiceProtocol.DEEPGRAM,
            baseUrl = "https://api.deepgram.com/v1", sttModel = "nova-3",
            ttsModel = "aura-2-thalia-en", ttsVoice = "aura-2-thalia-en",
            keyUrl = "https://console.deepgram.com/",
            voices = deepgramVoices, sttModels = deepgramSttModels, ttsModels = deepgramTtsModels,
        ),
        VoicePreset(
            kind = VoiceProviderKind.CARTESIA, name = "Cartesia", protocol = VoiceProtocol.CARTESIA,
            baseUrl = "https://api.cartesia.ai", supportsStt = false,
            ttsModel = "sonic-3", ttsVoice = "a0e99841-438c-4a64-b679-ae501e7d6091",
            keyUrl = "https://play.cartesia.ai/console",
            ttsModels = cartesiaTtsModels,
        ),
        VoicePreset(
            kind = VoiceProviderKind.ASSEMBLYAI, name = "AssemblyAI", protocol = VoiceProtocol.ASSEMBLYAI,
            baseUrl = "https://api.assemblyai.com", sttModel = "universal", supportsTts = false,
            keyUrl = "https://www.assemblyai.com/dashboard/api-keys",
            sttModels = assemblyAiSttModels,
        ),
    )

    fun byKind(kind: VoiceProviderKind): VoicePreset? = all.firstOrNull { it.kind == kind }

    fun sttModels(kind: VoiceProviderKind): List<String> = byKind(kind)?.sttModels.orEmpty()
    fun ttsModels(kind: VoiceProviderKind): List<String> = byKind(kind)?.ttsModels.orEmpty()
    fun voices(kind: VoiceProviderKind): List<String> = byKind(kind)?.voices.orEmpty()

    fun defaultVoice(kind: VoiceProviderKind, model: String): String? {
        val preset = byKind(kind) ?: return null
        val voices = when (kind) {
            VoiceProviderKind.OPENAI -> openAiVoices
            VoiceProviderKind.DEEPGRAM -> deepgramVoices
            VoiceProviderKind.EDGE -> EdgeVoiceCatalog.fallbackVoices
            else -> preset.voices
        }
        return voices.firstOrNull() ?: preset.ttsVoice.ifBlank { null }
    }
}

/** User-facing voice preferences. Device-local; keys live in the encrypted [ApiKeyVault]. */
data class VoiceSettings(
    /** Speech-to-text provider. DEVICE = the existing Android SpeechRecognizer (no key). */
    val sttKind: VoiceProviderKind = VoiceProviderKind.DEVICE,
    /** Text-to-speech provider. */
    val ttsKind: VoiceProviderKind = VoiceProviderKind.EDGE,
    val sttModel: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    /** BCP-47 tag or "auto"; resolves through [com.mobileharness.code.sidekick.SpeechLanguage]. */
    val voiceLanguage: String = "auto",
    /** Speech rate 0.5..2.0. */
    val speed: Float = 1.0f,
    /** Automatically read assistant replies aloud (opt-in, off by default). */
    val autoRead: Boolean = false,
    /** Live Mode master switch. */
    val liveModeEnabled: Boolean = false,
    val liveModeAccess: LiveModeAccess = LiveModeAccess.READ,
    /** In Live Mode, speak replies aloud. */
    val speakReplies: Boolean = true,
    /** Interrupt TTS playback when the user starts speaking (best effort). */
    val interruptOnSpeech: Boolean = false,
    /** Fallback TTS provider kind, or null. */
    val ttsFallbackKind: VoiceProviderKind? = null,
) {
    fun withSpeed(v: Float): VoiceSettings = copy(speed = v.coerceIn(0.5f, 2f))
}

/** JSON codec for [VoiceSettings]. Fail-closed: a malformed blob decodes to defaults. */
object VoiceSettingsCodec {
    fun toJson(s: VoiceSettings): String = JSONObject()
        .put("sttKind", s.sttKind.name)
        .put("ttsKind", s.ttsKind.name)
        .put("sttModel", s.sttModel)
        .put("ttsModel", s.ttsModel)
        .put("ttsVoice", s.ttsVoice)
        .put("voiceLanguage", s.voiceLanguage)
        .put("speed", s.speed.toDouble())
        .put("autoRead", s.autoRead)
        .put("liveModeEnabled", s.liveModeEnabled)
        .put("liveModeAccess", s.liveModeAccess.name)
        .put("speakReplies", s.speakReplies)
        .put("interruptOnSpeech", s.interruptOnSpeech)
        .put("ttsFallbackKind", s.ttsFallbackKind?.name ?: "")
        .toString()

    fun fromJson(raw: String): VoiceSettings {
        if (raw.isBlank()) return VoiceSettings()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return VoiceSettings()
        fun kind(name: String, default: VoiceProviderKind): VoiceProviderKind =
            runCatching { VoiceProviderKind.valueOf(name) }.getOrDefault(default)
        fun access(name: String): LiveModeAccess =
            runCatching { LiveModeAccess.valueOf(name) }.getOrDefault(LiveModeAccess.READ)
        return VoiceSettings(
            sttKind = kind(o.optString("sttKind"), VoiceProviderKind.DEVICE),
            ttsKind = kind(o.optString("ttsKind"), VoiceProviderKind.EDGE),
            sttModel = o.optString("sttModel"),
            ttsModel = o.optString("ttsModel"),
            ttsVoice = o.optString("ttsVoice"),
            voiceLanguage = o.optString("voiceLanguage", "auto"),
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2f),
            autoRead = o.optBoolean("autoRead", false),
            liveModeEnabled = o.optBoolean("liveModeEnabled", false),
            liveModeAccess = access(o.optString("liveModeAccess")),
            speakReplies = o.optBoolean("speakReplies", true),
            interruptOnSpeech = o.optBoolean("interruptOnSpeech", false),
            ttsFallbackKind = o.optString("ttsFallbackKind").takeIf { it.isNotBlank() }
                ?.let { name -> runCatching { VoiceProviderKind.valueOf(name) }.getOrNull() },
        )
    }
}
