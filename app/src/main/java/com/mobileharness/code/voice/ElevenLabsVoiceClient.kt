package com.mobileharness.code.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * ElevenLabs: `/v1/text-to-speech/{voice_id}` for output and `/v1/speech-to-text` (Scribe) for
 * input. Not OpenAI-compatible, so it gets its own thin client. Auth header is `xi-api-key`.
 */
object ElevenLabsVoiceClient {

    fun validateKey(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<Unit> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key cannot be blank"))
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/user/subscription", mapOf("xi-api-key" to key))
            if (resp.isSuccess) Unit
            else throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "ElevenLabs", key))
        }
    }

    fun listModels(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<List<String>> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key is missing"))
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/models", mapOf("xi-api-key" to key))
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "ElevenLabs", key))
            val element: Any = runCatching { JSONArray(resp.text()) }.getOrNull()
                ?: runCatching { JSONObject(resp.text()) }.getOrNull()
                ?: throw IllegalStateException("ElevenLabs returned an invalid model list")
            val array = when (element) {
                is JSONArray -> element
                is JSONObject -> element.optJSONArray("models") ?: JSONArray()
                else -> JSONArray()
            }
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                o.optString("model_id").takeIf { it.isNotBlank() } ?: o.optString("id").takeIf { it.isNotBlank() }
            }
        }
    }

    fun listVoices(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<List<Pair<String, String>>> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key is missing"))
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/voices", mapOf("xi-api-key" to key))
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "ElevenLabs", key))
            val root = JSONObject(resp.text())
            val arr = root.optJSONArray("voices") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("voice_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to o.optString("name", id)
            }
        }
    }

    fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        filename: String = "speech.mp4",
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<String> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("ElevenLabs API key is missing"))
        return runCatching {
            val resp = transport.postMultipart(
                "${baseUrl.trimEnd('/')}/speech-to-text",
                mapOf("xi-api-key" to key),
                "file", filename, audioBytes,
                mapOf("model_id" to model.ifBlank { "scribe_v1" }),
            )
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "ElevenLabs", key))
            val text = runCatching { JSONObject(resp.text()).optString("text") }.getOrNull()?.trim()
            if (text.isNullOrBlank()) throw IllegalStateException("ElevenLabs returned an invalid transcription.")
            text
        }
    }

    fun synthesize(
        baseUrl: String,
        apiKey: String,
        text: String,
        model: String,
        voice: String,
        speed: Float,
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<ByteArray> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("ElevenLabs API key is missing"))
        if (voice.isBlank()) return Result.failure(IllegalStateException("Choose an ElevenLabs voice"))
        if (text.isBlank() || text.none(Char::isLetterOrDigit)) return Result.failure(IllegalArgumentException("Nothing to speak"))
        val body = JSONObject()
            .put("text", text)
            .put("model_id", model.ifBlank { "eleven_multilingual_v2" })
            .put("output_format", "mp3_44100_128")
            .toString()
        return runCatching {
            val url = "${baseUrl.trimEnd('/')}/text-to-speech/${voice.trim()}" +
                "?optimize_streaming_latency=2&output_format=mp3_44100_128"
            val resp = transport.postJson(url, mapOf("xi-api-key" to key), body)
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "ElevenLabs", key))
            if (resp.body.isEmpty()) throw IllegalStateException("ElevenLabs returned no audio.")
            resp.body
        }
    }
}
