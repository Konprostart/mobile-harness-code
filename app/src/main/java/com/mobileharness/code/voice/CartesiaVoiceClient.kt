package com.mobileharness.code.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cartesia Sonic: POST `/tts/bytes` with a `Cartesia-Version` header. Output only; the app falls
 * back to another provider for speech-to-text. Auth header is `X-API-Key`.
 */
object CartesiaVoiceClient {

    /** Cartesia pins api_version per release; a wrong one is rejected outright. */
    const val API_VERSION = "2024-06-10"

    fun validateKey(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<Unit> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key cannot be blank"))
        return runCatching {
            val resp = transport.get(
                "${baseUrl.trimEnd('/')}/voices?limit=1",
                mapOf("X-API-Key" to key, "Cartesia-Version" to API_VERSION),
            )
            if (resp.isSuccess) Unit
            else throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Cartesia", key))
        }
    }

    fun listVoices(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<List<Pair<String, String>>> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key is missing"))
        return runCatching {
            val resp = transport.get(
                "${baseUrl.trimEnd('/')}/voices?limit=100",
                mapOf("X-API-Key" to key, "Cartesia-Version" to API_VERSION),
            )
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Cartesia", key))
            val root = JSONObject(resp.text())
            val arr = root.optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to o.optString("name", id)
            }
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
        if (key.isBlank()) return Result.failure(IllegalStateException("Cartesia API key is missing"))
        if (voice.isBlank()) return Result.failure(IllegalStateException("Choose a Cartesia voice"))
        if (text.isBlank() || text.none(Char::isLetterOrDigit)) return Result.failure(IllegalArgumentException("Nothing to speak"))
        val body = JSONObject()
            .put("model_id", model.ifBlank { "sonic-2" })
            .put("transcript", text)
            .put("voice", JSONObject().put("mode", "id").put("id", voice.trim()))
            .put("output_format", JSONObject().put("container", "mp3").put("sample_rate", 44100).put("bit_rate", 128_000))
            .put("speed", speed.coerceIn(0.5f, 2f).toDouble())
            .toString()
        return runCatching {
            val resp = transport.postJson(
                "${baseUrl.trimEnd('/')}/tts/bytes",
                mapOf("X-API-Key" to key, "Cartesia-Version" to API_VERSION),
                body,
            )
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Cartesia", key))
            if (resp.body.isEmpty()) throw IllegalStateException("Cartesia returned no audio.")
            resp.body
        }
    }
}
