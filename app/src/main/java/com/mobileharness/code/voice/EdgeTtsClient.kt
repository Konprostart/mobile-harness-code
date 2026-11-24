package com.mobileharness.code.voice

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Microsoft Edge's Read Aloud service, used directly: no Azure subscription, no key, no
 * third-party router. Synthesis is a WebSocket session (connect, send `speech.config`, send SSML,
 * collect binary audio frames until `turn.end`). The voice catalogue is a separate plain HTTP call.
 *
 * Authentication is a derived token `Sec-MS-GEC`, computed from the current time:
 *  - the timestamp is Windows file time (unix + 11644473600) in 100 ns ticks, rounded DOWN to a
 *    300 second window, then multiplied by 10^7;
 *  - the digest is uppercase hex, concatenated with the fixed client token.
 *
 * Requires internet; it is NOT an offline TTS. Pure logic ported from AndroidHarness 4.0
 * (MIT, Copyright 2026 Sanuu7) — see NOTICE.
 */
object EdgeTtsClient {

    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_HOST = "api.msedgeservices.com"
    private const val WSS_PATH = "/tts/cognitiveservices/websocket/v1"

    const val VOICE_LIST_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=$TRUSTED_TOKEN"

    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    const val MAX_INPUT_CHARS = 4_000

    @Volatile
    var chromiumVersion: String = FALLBACK_CHROMIUM_VERSION

    const val FALLBACK_CHROMIUM_VERSION = "156.0.4314.80"

    internal fun gecVersion(): String = "1-$chromiumVersion"

    private const val WINDOWS_EPOCH_OFFSET_SECONDS = 11_644_473_600L
    private const val GEC_WINDOW_SECONDS = 300L

    internal fun gecToken(nowMillis: Long): String {
        val unixSeconds = nowMillis / 1000
        val rounded = unixSeconds - (unixSeconds % GEC_WINDOW_SECONDS)
        val windowsTicks = (rounded + WINDOWS_EPOCH_OFFSET_SECONDS) * 10_000_000L
        return sha256Hex("$windowsTicks$TRUSTED_TOKEN").uppercase(Locale.US)
    }

    internal fun timestamp(nowMillis: Long): String {
        val format = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return format.format(Date(nowMillis))
    }

    internal fun voiceTag(shortName: String): String {
        val parts = shortName.split('-')
        if (parts.size < 3) return "Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)"
        val locale = parts[0] + "-" + parts[1]
        val name = parts.drop(2).joinToString("-")
        return "Microsoft Server Speech Text to Speech Voice ($locale, $name)"
    }

    internal fun escapeSsml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    internal fun ssml(text: String, shortName: String, speed: Float): String {
        val percent = ((speed.coerceIn(0.5f, 2f) - 1f) * 100).toInt()
        val sign = if (percent >= 0) "+" else ""
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${voiceTag(shortName)}'>" +
            "<prosody rate='$sign$percent%' pitch='+0Hz'>${escapeSsml(text)}</prosody>" +
            "</voice></speak>"
    }

    internal fun sessionUrl(nowMillis: Long): String =
        "wss://$WSS_HOST$WSS_PATH?Ocp-Apim-Subscription-Key=$TRUSTED_TOKEN" +
            "&Sec-MS-GEC=${gecToken(nowMillis)}" +
            "&Sec-MS-GEC-Version=${gecVersion()}" +
            "&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"

    internal fun handshakeHeaders(): Map<String, String> {
        val major = chromiumVersion.substringBefore('.')
        return mapOf(
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
            "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
            "Accept-Encoding" to "gzip, deflate, br",
            "Accept-Language" to "en-US,en;q=0.9",
            "User-Agent" to (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0"
                ),
        )
    }

    internal fun audioFromFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val start = 2 + headerLength
        if (headerLength <= 0 || start >= frame.size) return null
        return frame.copyOfRange(start, frame.size)
    }

    /** Synthesizes one clip and returns MP3 bytes. Blocks on the WebSocket session. */
    fun synthesize(
        text: String,
        shortName: String,
        speed: Float = 1f,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<ByteArray> {
        if (text.isBlank() || text.none(Char::isLetterOrDigit)) {
            return Result.failure(IllegalArgumentException("Nothing to speak"))
        }
        if (shortName.isBlank()) return Result.failure(IllegalStateException("Choose an Edge voice"))
        val clip = text.take(MAX_INPUT_CHARS)
        return runCatching { requestAudio(clip, shortName, speed, nowMillis) }
    }

    private fun requestAudio(text: String, shortName: String, speed: Float, nowMillis: Long): ByteArray {
        val socket = openSocket(nowMillis)
        try {
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            // 1. Session config: chooses the audio format for everything that follows.
            sendText(
                output,
                "X-Timestamp:${timestamp(nowMillis)}\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{""" +
                    """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                    """"outputFormat":"$OUTPUT_FORMAT"}}}}""",
            )
            // 2. The text itself, as SSML for the chosen voice.
            val requestId = UUID.randomUUID().toString().replace("-", "")
            sendText(
                output,
                "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${timestamp(nowMillis)}Z\r\n" +
                    "Path:ssml\r\n\r\n" +
                    ssml(text, shortName, speed),
            )
            val audio = ByteArrayOutputStream()
            while (true) {
                val frame = readFrame(input) ?: throw IOException("Edge closed the session early")
                when (frame) {
                    is EdgeFrame.Binary -> audioFromFrame(frame.bytes)?.let { audio.write(it, 0, it.size) }
                    is EdgeFrame.Text -> if (frame.text.contains("Path:turn.end")) {
                        val bytes = audio.toByteArray()
                        if (bytes.isEmpty()) throw IOException("Edge returned no audio")
                        return bytes
                    }
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun openSocket(nowMillis: Long): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket(WSS_HOST, 443) as SSLSocket
        socket.soTimeout = 60_000
        val url = sessionUrl(nowMillis)
        val pathAndQuery = url.substringAfter("wss://$WSS_HOST")
        val key = Base64.getEncoder().encodeToString(randomBytes(16))
        val output = DataOutputStream(socket.outputStream)
        val sb = StringBuilder()
        sb.append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(WSS_HOST).append("\r\n")
        sb.append("Upgrade: websocket\r\n")
        sb.append("Connection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        sb.append("Sec-WebSocket-Version: 13\r\n")
        handshakeHeaders().forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.flush()
        // Read the handshake response line and headers.
        val input = DataInputStream(socket.inputStream)
        val statusLine = readLine(input) ?: throw IOException("Edge handshake failed")
        if (!statusLine.contains("101")) throw IOException("Edge handshake rejected ($statusLine)")
        var line: String?
        do {
            line = readLine(input)
        } while (line != null && line.isNotEmpty())
        return socket
    }

    private fun sendText(output: DataOutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val mask = randomBytes(4)
        val out = ByteArrayOutputStream()
        out.write(0x81) // FIN + text opcode
        writeLength(out, bytes.size, masked = true)
        out.write(mask)
        for (i in bytes.indices) out.write(bytes[i].toInt() xor mask[i % 4].toInt())
        output.write(out.toByteArray())
        output.flush()
    }

    private fun readFrame(input: DataInputStream): EdgeFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) length = input.readUnsignedShort().toLong()
        else if (length == 127L) length = input.readLong()
        val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        input.readFully(payload)
        if (masked && maskKey != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        return when (opcode) {
            0x1 -> EdgeFrame.Text(String(payload, Charsets.UTF_8))
            0x2 -> EdgeFrame.Binary(payload)
            0x8 -> null // close
            0x9, 0xA -> null // ping/pong ignored
            else -> EdgeFrame.Binary(payload)
        }
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int, masked: Boolean) {
        val maskBit = if (masked) 0x80 else 0
        when {
            length < 126 -> out.write(maskBit or length)
            length < 65536 -> {
                out.write(maskBit or 126)
                out.write((length ushr 8) and 0xFF)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                for (i in 7 downTo 0) out.write(((length.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
    }

    private fun readLine(input: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

sealed class EdgeFrame {
    data class Text(val text: String) : EdgeFrame()
    data class Binary(val bytes: ByteArray) : EdgeFrame()
}
