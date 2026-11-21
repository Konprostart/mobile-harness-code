package com.mobileharness.code.voice

import org.json.JSONObject

/**
 * HTTP client for cloud voice services (OpenAI-compatible STT/TTS).
 *
 * Voice services are plain HTTP endpoints, so this client speaks the wire format directly rather
 * than bundling a proprietary SDK. The transport is injected so the wire format is unit-testable
 * without a network. It never logs the API key.
 */
class VoiceApiClient(
    private val transport: Transport = HttpTransport,
) {
    interface Transport {
        /** POST with multipart/form-data (STT). Returns the raw response. */
        fun postMultipart(
            url: String,
            headers: Map<String, String>,
            fieldName: String,
            filename: String,
            audioBytes: ByteArray,
        ): Response

        /** POST with a JSON body (TTS and custom endpoints). */
        fun postJson(url: String, headers: Map<String, String>, body: String): Response
    }

    data class Response(val code: Int, val body: ByteArray, val error: String? = null)

    class VoiceException(message: String) : Exception(message)

    /** Transcribes [audioBytes] with an OpenAI-compatible `/audio/transcriptions` endpoint. */
    fun transcribe(profile: VoiceProfile, audioBytes: ByteArray): String {
        val base = baseUrl(profile)
        val resp = transport.postMultipart(
            url = "$base/audio/transcriptions",
            headers = authHeaders(profile),
            fieldName = "file",
            filename = "speech.webm",
            audioBytes = audioBytes,
        )
        if (resp.code !in 200..299) throw VoiceException("STT failed (${resp.code}): ${resp.error ?: "unknown error"}")
        return parseTranscription(resp.body, profile.model)
    }

    /** Synthesizes [text] with an OpenAI-compatible `/audio/speech` endpoint. */
    fun synthesize(profile: VoiceProfile, text: String): ByteArray {
        val base = baseUrl(profile)
        val body = JSONObject()
            .put("model", profile.model.ifBlank { VoiceProviders.defaultModel(profile.kind) })
            .put("voice", profile.voice.ifBlank { "alloy" })
            .put("input", text)
            .toString()
        val resp = transport.postJson(
            url = "$base/audio/speech",
            headers = authHeaders(profile),
            body = body,
        )
        if (resp.code !in 200..299) throw VoiceException("TTS failed (${resp.code}): ${resp.error ?: "unknown error"}")
        if (resp.body.isEmpty()) throw VoiceException("TTS returned empty audio.")
        return resp.body
    }

    private fun baseUrl(profile: VoiceProfile): String {
        val raw = profile.baseUrl.ifBlank { VoiceProviders.defaultBaseUrl(profile.kind) }
        return raw.trimEnd('/')
    }

    private fun authHeaders(profile: VoiceProfile): Map<String, String> =
        if (profile.apiKey.isBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer ${profile.apiKey}", "Content-Type" to "application/json")

    private fun parseTranscription(body: ByteArray, model: String): String {
        val text = String(body, Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return text.trim().ifBlank { throw VoiceException("Empty transcription.") }
        val result = obj.optString("text")
        if (result.isNotBlank()) return result
        throw VoiceException("Transcription response did not contain text.")
    }
}

/** Default transport backed by HttpURLConnection. */
object HttpTransport : VoiceApiClient.Transport {
    override fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fieldName: String,
        filename: String,
        audioBytes: ByteArray,
    ): VoiceApiClient.Response {
        return request(url, headers, null) { conn, out ->
            val boundary = "----mobileharness" + System.nanoTime()
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\"\r\n".toByteArray())
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            out.write(audioBytes)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
    }

    override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): VoiceApiClient.Response {
        return request(url, headers, body.toByteArray()) { conn, out ->
            conn.setRequestProperty("Content-Type", "application/json")
            out.write(body.toByteArray())
        }
    }

    private inline fun request(
        url: String,
        headers: Map<String, String>,
        initial: ByteArray?,
        write: (java.net.HttpURLConnection, java.io.OutputStream) -> Unit,
    ): VoiceApiClient.Response {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { out -> write(conn, out) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            VoiceApiClient.Response(code, body, if (code in 200..299) null else String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            VoiceApiClient.Response(-1, ByteArray(0), e.message ?: e.javaClass.simpleName)
        }
    }
}
