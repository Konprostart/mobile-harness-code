package com.mobileharness.code.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deepgram: `/v1/listen` transcribes and `/v1/speak` (Aura) synthesizes. Both take the raw audio
 * body with a `Token` auth header, not Bearer.
 */
object DeepgramVoiceClient {

    fun validateKey(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<Unit> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key cannot be blank"))
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/auth/token", mapOf("Authorization" to "Token $key"))
            if (resp.isSuccess) Unit
            else throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Deepgram", key))
        }
    }

    fun listModels(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<List<String>> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key is missing"))
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/models", mapOf("Authorization" to "Token $key"))
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Deepgram", key))
            val root = JSONObject(resp.text())
            val out = mutableListOf<String>()
            for (section in listOf("stt", "tts")) {
                val arr = root.optJSONArray(section) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    o.optString("canonical_name").takeIf { it.isNotBlank() }?.let(out::add)
                        ?: o.optString("name").takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
            out.distinct()
        }
    }

    fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<String> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("Deepgram API key is missing"))
        val chosen = model.ifBlank { "nova-3" }
        return runCatching {
            val url = "${baseUrl.trimEnd('/')}/listen?model=$chosen&smart_format=true&punctuate=true"
            val resp = transport.postRaw(url, mapOf("Authorization" to "Token $key"), audioBytes, "audio/webm")
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Deepgram", key))
            val transcript = runCatching {
                JSONObject(resp.text())
                    .optJSONObject("results")
                    ?.optJSONArray("channels")
                    ?.optJSONObject(0)
                    ?.optJSONArray("alternatives")
                    ?.optJSONObject(0)
                    ?.optString("transcript")
            }.getOrNull()?.trim().orEmpty()
            transcript
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
        if (key.isBlank()) return Result.failure(IllegalStateException("Deepgram API key is missing"))
        if (text.isBlank() || text.none(Char::isLetterOrDigit)) return Result.failure(IllegalArgumentException("Nothing to speak"))
        val chosenModel = model.ifBlank { voice }.ifBlank { "aura-2-thalia-en" }
        return runCatching {
            val resp = transport.postJson(
                "${baseUrl.trimEnd('/')}/speak?model=$chosenModel",
                mapOf("Authorization" to "Token $key"),
                JSONObject().put("text", text).toString(),
            )
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Deepgram", key))
            if (resp.body.isEmpty()) throw IllegalStateException("Deepgram returned no audio.")
            resp.body
        }
    }
}
