package com.mobileharness.code.voice

/**
 * One entry point for speech-to-text and text-to-speech, so callers never need to know which
 * vendor is configured. Every member dispatches on the provider kind to the matching client.
 *
 * `DEVICE` STT is handled by the Android system recognizer, not this gateway; network synthesis
 * and transcription always go through here.
 */
object VoiceGateway {

    fun protocol(kind: VoiceProviderKind): VoiceProtocol? = when (kind) {
        VoiceProviderKind.CUSTOM -> VoiceProtocol.OPENAI_AUDIO
        VoiceProviderKind.DEVICE -> null
        else -> VoicePresets.byKind(kind)?.protocol
    }

    /** Resolves the base URL, preferring the user override and falling back to the preset default. */
    fun resolvedBaseUrl(kind: VoiceProviderKind, baseUrl: String): String {
        val raw = baseUrl.ifBlank {
            VoicePresets.byKind(kind)?.baseUrl ?: VoiceProviders.defaultBaseUrl(kind)
        }
        return raw.trim().trimEnd('/')
    }

    fun validateKey(
        kind: VoiceProviderKind,
        baseUrl: String = "",
        apiKey: String = "",
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<Unit> = when (protocol(kind)) {
        VoiceProtocol.OPENAI_AUDIO -> OpenAiVoiceClient.validateKey(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.ELEVENLABS -> ElevenLabsVoiceClient.validateKey(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.DEEPGRAM -> DeepgramVoiceClient.validateKey(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.CARTESIA -> CartesiaVoiceClient.validateKey(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.ASSEMBLYAI -> AssemblyAiVoiceClient.validateKey(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        // Edge needs no credential, so there is nothing to validate.
        VoiceProtocol.EDGE -> Result.success(Unit)
        null -> Result.failure(UnsupportedOperationException("No network voice endpoint for this provider"))
    }

    fun listModels(
        kind: VoiceProviderKind,
        baseUrl: String = "",
        apiKey: String = "",
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<List<String>> = when (protocol(kind)) {
        VoiceProtocol.OPENAI_AUDIO -> OpenAiVoiceClient.listModels(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.ELEVENLABS -> ElevenLabsVoiceClient.listModels(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.DEEPGRAM -> DeepgramVoiceClient.listModels(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        else -> Result.success(VoicePresets.byKind(kind)?.ttsModels.orEmpty())
    }

    fun listVoices(
        kind: VoiceProviderKind,
        baseUrl: String = "",
        apiKey: String = "",
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<List<Pair<String, String>>> = when (protocol(kind)) {
        VoiceProtocol.ELEVENLABS -> ElevenLabsVoiceClient.listVoices(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.CARTESIA -> CartesiaVoiceClient.listVoices(resolvedBaseUrl(kind, baseUrl), apiKey, transport)
        VoiceProtocol.EDGE -> Result.success(EdgeVoiceCatalog.fetch(transport).map { it to it })
        else -> Result.success(VoicePresets.voices(kind).map { it to it })
    }

    fun transcribe(
        kind: VoiceProviderKind,
        baseUrl: String = "",
        apiKey: String = "",
        model: String = "",
        language: String? = null,
        audioBytes: ByteArray,
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<String> = when (protocol(kind)) {
        VoiceProtocol.OPENAI_AUDIO -> OpenAiVoiceClient.transcribe(
            resolvedBaseUrl(kind, baseUrl), apiKey, model.ifBlank { presetSttModel(kind) }, language, audioBytes, transport = transport,
        )
        VoiceProtocol.ELEVENLABS -> ElevenLabsVoiceClient.transcribe(
            resolvedBaseUrl(kind, baseUrl), apiKey, model.ifBlank { presetSttModel(kind) }, audioBytes, transport = transport,
        )
        VoiceProtocol.DEEPGRAM -> DeepgramVoiceClient.transcribe(
            resolvedBaseUrl(kind, baseUrl), apiKey, model.ifBlank { presetSttModel(kind) }, audioBytes, transport = transport,
        )
        VoiceProtocol.ASSEMBLYAI -> AssemblyAiVoiceClient.transcribe(
            resolvedBaseUrl(kind, baseUrl), apiKey, model.ifBlank { presetSttModel(kind) }, audioBytes, transport = transport,
        )
        VoiceProtocol.CARTESIA -> Result.failure(UnsupportedOperationException("Cartesia does not offer speech to text"))
        VoiceProtocol.EDGE -> Result.failure(UnsupportedOperationException("Edge does not offer speech to text"))
        null -> Result.failure(UnsupportedOperationException("No network speech-to-text endpoint for this provider"))
    }

    fun synthesize(
        kind: VoiceProviderKind,
        baseUrl: String = "",
        apiKey: String = "",
        text: String,
        model: String = "",
        voice: String = "",
        speed: Float = 1f,
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<ByteArray> = when (protocol(kind)) {
        VoiceProtocol.OPENAI_AUDIO -> OpenAiVoiceClient.synthesize(
            resolvedBaseUrl(kind, baseUrl), apiKey, text, model.ifBlank { presetTtsModel(kind) },
            voice.ifBlank { VoicePresets.defaultVoice(kind, model).orEmpty() }, speed, transport = transport,
        )
        VoiceProtocol.ELEVENLABS -> ElevenLabsVoiceClient.synthesize(
            resolvedBaseUrl(kind, baseUrl), apiKey, text, model.ifBlank { presetTtsModel(kind) },
            voice.ifBlank { presetTtsVoice(kind) }, speed, transport = transport,
        )
        VoiceProtocol.DEEPGRAM -> DeepgramVoiceClient.synthesize(
            resolvedBaseUrl(kind, baseUrl), apiKey, text, model.ifBlank { presetTtsModel(kind) },
            voice.ifBlank { presetTtsVoice(kind) }, speed, transport = transport,
        )
        VoiceProtocol.CARTESIA -> CartesiaVoiceClient.synthesize(
            resolvedBaseUrl(kind, baseUrl), apiKey, text, model.ifBlank { presetTtsModel(kind) },
            voice.ifBlank { presetTtsVoice(kind) }, speed, transport = transport,
        )
        VoiceProtocol.EDGE -> EdgeTtsClient.synthesize(text, voice.ifBlank { EdgeVoiceCatalog.fallbackVoices.first() }, speed)
        VoiceProtocol.ASSEMBLYAI -> Result.failure(UnsupportedOperationException("AssemblyAI does not offer text to speech"))
        null -> Result.failure(UnsupportedOperationException("No network text-to-speech endpoint for this provider"))
    }

    private fun presetSttModel(kind: VoiceProviderKind): String = VoicePresets.byKind(kind)?.sttModel.orEmpty()
    private fun presetTtsModel(kind: VoiceProviderKind): String = VoicePresets.byKind(kind)?.ttsModel.orEmpty()
    private fun presetTtsVoice(kind: VoiceProviderKind): String = VoicePresets.byKind(kind)?.ttsVoice.orEmpty()
}
