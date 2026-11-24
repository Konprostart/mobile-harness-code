package com.mobileharness.code.voice

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP response shared by every voice client. */
data class VoiceResponse(val code: Int, val body: ByteArray, val error: String? = null) {
    val isSuccess: Boolean get() = code in 200..299
    fun text(): String = String(body, Charsets.UTF_8)
}

/**
 * HTTP transport for the voice layer. It is injected so every provider's wire format is
 * unit-testable without a network. It never logs the API key.
 */
interface VoiceTransport {
    fun get(url: String, headers: Map<String, String>): VoiceResponse
    fun postJson(url: String, headers: Map<String, String>, body: String): VoiceResponse
    fun postRaw(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): VoiceResponse
    fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fieldName: String,
        filename: String,
        audioBytes: ByteArray,
        extraFields: Map<String, String> = emptyMap(),
    ): VoiceResponse
}

/** Default transport backed by HttpURLConnection. */
object HttpVoiceTransport : VoiceTransport {
    override fun get(url: String, headers: Map<String, String>): VoiceResponse =
        request(url, headers, null, "GET") { _, _ -> }

    override fun postJson(url: String, headers: Map<String, String>, body: String): VoiceResponse =
        request(url, headers, body.toByteArray(Charsets.UTF_8), "POST") { conn, out ->
            conn.setRequestProperty("Content-Type", "application/json")
            out.write(body.toByteArray(Charsets.UTF_8))
        }

    override fun postRaw(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentType: String,
    ): VoiceResponse = request(url, headers, body, "POST") { conn, out ->
        conn.setRequestProperty("Content-Type", contentType)
        out.write(body)
    }

    override fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fieldName: String,
        filename: String,
        audioBytes: ByteArray,
        extraFields: Map<String, String>,
    ): VoiceResponse = request(url, headers, null, "POST") { conn, out ->
        val boundary = "----mobileharness" + System.nanoTime()
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        extraFields.forEach { (k, v) ->
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
            write("$v\r\n")
        }
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\"\r\n")
        write("Content-Type: application/octet-stream\r\n\r\n")
        out.write(audioBytes)
        write("\r\n--$boundary--\r\n")
    }

    private inline fun request(
        url: String,
        headers: Map<String, String>,
        initial: ByteArray?,
        method: String,
        write: (HttpURLConnection, OutputStream) -> Unit,
    ): VoiceResponse {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.requestMethod = method
            conn.doOutput = initial != null || method != "GET"
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.doOutput) {
                conn.outputStream.use { out -> write(conn, out) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            VoiceResponse(code, body, if (code in 200..299) null else String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            VoiceResponse(-1, ByteArray(0), e.message ?: e.javaClass.simpleName)
        }
    }
}
