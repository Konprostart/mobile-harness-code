package com.mobileharness.code.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * The OpenAI Audio wire format, which OpenAI, Groq and most OpenAI-compatible voice servers speak:
 * multipart `/audio/transcriptions` in, JSON `/audio/speech` out.
 */
object OpenAiVoiceClient {

    fun validateKey(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<Unit> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key cannot be blank"))
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/models", mapOf("Authorization" to "Bearer $key"))
            if (resp.isSuccess) Unit
            else throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Voice", key))
        }
    }

    fun listModels(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<List<String>> {
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/models", headers)
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Voice", apiKey))
            val root = JSONObject(resp.text())
            val data = root.optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String?,
        audioBytes: ByteArray,
        filename: String = "speech.webm",
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<String> {
        if (model.isBlank()) return Result.failure(IllegalStateException("Choose a transcription model"))
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        val extra = mutableMapOf("model" to model, "response_format" to "json")
        language?.takeIf { it.isNotBlank() }?.let { extra["language"] = it }
        return runCatching {
            val resp = transport.postMultipart(
                "${baseUrl.trimEnd('/')}/audio/transcriptions", headers, "file", filename, audioBytes, extra,
            )
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Transcription", apiKey))
            val text = runCatching { JSONObject(resp.text()).optString("text") }.getOrNull()?.trim()
            if (text.isNullOrBlank()) throw IllegalStateException("The provider returned an invalid transcription.")
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
        responseFormat: String = "mp3",
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<ByteArray> {
        if (text.isBlank() || text.none(Char::isLetterOrDigit)) return Result.failure(IllegalArgumentException("Nothing to speak"))
        if (model.isBlank()) return Result.failure(IllegalStateException("Choose a speech model"))
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        val body = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice.ifBlank { "alloy" })
            .put("response_format", responseFormat)
            .put("speed", speed.coerceIn(0.5f, 2f).toDouble())
            .toString()
        return runCatching {
            val resp = transport.postJson("${baseUrl.trimEnd('/')}/audio/speech", headers, body)
            if (!resp.isSuccess) throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "Speech", apiKey))
            if (resp.body.isEmpty()) throw IllegalStateException("The provider returned no audio.")
            if (responseFormat == "wav") fixStreamingWav(resp.body) else resp.body
        }
    }

    /**
     * Some models (Orpheus) stream WAV and leave the RIFF/data sizes at 0xFFFFFFFF. Android's
     * parser trusts those fields and refuses the file, so stamp the real sizes.
     */
    internal fun fixStreamingWav(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return bytes
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return bytes
        val out = bytes.copyOf()
        fun readU32(offset: Int): Long =
            (out[offset].toLong() and 0xFF) or
                ((out[offset + 1].toLong() and 0xFF) shl 8) or
                ((out[offset + 2].toLong() and 0xFF) shl 16) or
                ((out[offset + 3].toLong() and 0xFF) shl 24)
        fun writeU32(offset: Int, value: Long) {
            for (i in 0 until 4) out[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        var offset = 12
        var dataOffset = -1
        while (offset + 8 <= out.size) {
            val id = String(out, offset, 4, Charsets.US_ASCII)
            val size = readU32(offset + 4)
            if (id == "data") { dataOffset = offset; break }
            if (size == 0xFFFFFFFFL) break
            val next = offset + 8 + size
            if (next <= offset || next > out.size) break
            offset = next.toInt()
        }
        if (dataOffset < 0) return out
        val riffSize = out.size - 8L
        val dataSize = out.size - dataOffset - 8L
        if (readU32(4) != riffSize) writeU32(4, riffSize)
        if (readU32(dataOffset + 4) != dataSize) writeU32(dataOffset + 4, dataSize)
        return out
    }
}
