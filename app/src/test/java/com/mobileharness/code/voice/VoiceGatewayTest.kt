package com.mobileharness.code.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records requests and returns a canned response, so each wire format is verified without a network. */
private class FakeTransport(
    private val respond: (String, Map<String, String>, ByteArray?) -> VoiceResponse,
) : VoiceTransport {
    var lastUrl: String = ""
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody: ByteArray = ByteArray(0)
    var lastExtraFields: Map<String, String> = emptyMap()

    override fun get(url: String, headers: Map<String, String>): VoiceResponse {
        lastUrl = url; lastHeaders = headers; lastBody = ByteArray(0)
        return respond(url, headers, null)
    }

    override fun postJson(url: String, headers: Map<String, String>, body: String): VoiceResponse {
        lastUrl = url; lastHeaders = headers; lastBody = body.toByteArray()
        return respond(url, headers, body.toByteArray())
    }

    override fun postRaw(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): VoiceResponse {
        lastUrl = url; lastHeaders = headers; lastBody = body
        return respond(url, headers, body)
    }

    override fun postMultipart(
        url: String, headers: Map<String, String>, fieldName: String, filename: String,
        audioBytes: ByteArray, extraFields: Map<String, String>,
    ): VoiceResponse {
        lastUrl = url; lastHeaders = headers; lastBody = audioBytes; lastExtraFields = extraFields
        return respond(url, headers, audioBytes)
    }
}

private fun okJson(body: String) = VoiceResponse(200, body.toByteArray(), null)
private fun okAudio() = VoiceResponse(200, byteArrayOf(1, 2, 3), null)

class VoiceGatewayTest {

    @Test
    fun `openai stt posts multipart with bearer and model`() {
        val t = FakeTransport { _, _, _ -> okJson("""{"text":"hello"}""") }
        val result = VoiceGateway.transcribe(
            VoiceProviderKind.OPENAI, apiKey = "k", model = "whisper-1", audioBytes = byteArrayOf(9), transport = t,
        )
        assertEquals("hello", result.getOrThrow())
        assertTrue(t.lastUrl.endsWith("/audio/transcriptions"))
        assertEquals("Bearer k", t.lastHeaders["Authorization"])
        assertEquals("whisper-1", t.lastExtraFields["model"])
        assertEquals("json", t.lastExtraFields["response_format"])
    }

    @Test
    fun `groq resolves default base url`() {
        val t = FakeTransport { _, _, _ -> okJson("""{"text":"hi"}""") }
        VoiceGateway.transcribe(VoiceProviderKind.GROQ, apiKey = "k", audioBytes = byteArrayOf(9), transport = t)
        assertTrue(t.lastUrl.startsWith("https://api.groq.com/openai/v1/audio/transcriptions"))
    }

    @Test
    fun `openai tts posts json to audio speech`() {
        val t = FakeTransport { _, _, _ -> okAudio() }
        val result = VoiceGateway.synthesize(
            VoiceProviderKind.OPENAI, apiKey = "k", text = "hello", model = "tts-1", voice = "alloy", transport = t,
        )
        assertTrue(result.isSuccess)
        assertTrue(t.lastUrl.endsWith("/audio/speech"))
        assertEquals("Bearer k", t.lastHeaders["Authorization"])
        val body = JSONObject(String(t.lastBody))
        assertEquals("tts-1", body.getString("model"))
        assertEquals("alloy", body.getString("voice"))
    }

    @Test
    fun `elevenlabs stt uses xi-api-key and speech-to-text`() {
        val t = FakeTransport { _, _, _ -> okJson("""{"text":"hello"}""") }
        VoiceGateway.transcribe(VoiceProviderKind.ELEVENLABS, apiKey = "k", audioBytes = byteArrayOf(9), transport = t)
        assertTrue(t.lastUrl.endsWith("/speech-to-text"))
        assertEquals("k", t.lastHeaders["xi-api-key"])
        assertEquals("scribe_v1", t.lastExtraFields["model_id"])
    }

    @Test
    fun `elevenlabs tts posts to voice id`() {
        val t = FakeTransport { _, _, _ -> okAudio() }
        VoiceGateway.synthesize(
            VoiceProviderKind.ELEVENLABS, apiKey = "k", text = "hello", voice = "v123", transport = t,
        )
        assertTrue(t.lastUrl.contains("/text-to-speech/v123"))
        assertEquals("k", t.lastHeaders["xi-api-key"])
    }

    @Test
    fun `deepgram stt uses token header and listen`() {
        val t = FakeTransport { _, _, _ -> okJson("""{"results":{"channels":[{"alternatives":[{"transcript":"hi"}]}]}}""") }
        val result = VoiceGateway.transcribe(VoiceProviderKind.DEEPGRAM, apiKey = "k", audioBytes = byteArrayOf(9), transport = t)
        assertEquals("hi", result.getOrThrow())
        assertTrue(t.lastUrl.contains("/listen?"))
        assertTrue(t.lastUrl.contains("smart_format=true"))
        assertEquals("Token k", t.lastHeaders["Authorization"])
    }

    @Test
    fun `deepgram tts posts to speak`() {
        val t = FakeTransport { _, _, _ -> okAudio() }
        VoiceGateway.synthesize(VoiceProviderKind.DEEPGRAM, apiKey = "k", text = "hello", transport = t)
        assertTrue(t.lastUrl.contains("/speak?"))
        assertEquals("Token k", t.lastHeaders["Authorization"])
    }

    @Test
    fun `cartesia tts uses x-api-key and version header`() {
        val t = FakeTransport { _, _, _ -> okAudio() }
        VoiceGateway.synthesize(VoiceProviderKind.CARTESIA, apiKey = "k", text = "hello", voice = "v", transport = t)
        assertTrue(t.lastUrl.endsWith("/tts/bytes"))
        assertEquals("k", t.lastHeaders["X-API-Key"])
        assertEquals(CartesiaVoiceClient.API_VERSION, t.lastHeaders["Cartesia-Version"])
    }

    @Test
    fun `assemblyai transcribe uploads then polls`() {
        var calls = 0
        val t = FakeTransport { url, _, _ ->
            calls++
            when {
                url.endsWith("/v2/upload") -> okJson("""{"upload_url":"https://u"}""")
                url.endsWith("/v2/transcript") -> okJson("""{"id":"job1"}""")
                url.contains("/v2/transcript/job1") -> okJson("""{"status":"completed","text":"done"}""")
                else -> VoiceResponse(404, ByteArray(0), "not found")
            }
        }
        val result = VoiceGateway.transcribe(VoiceProviderKind.ASSEMBLYAI, apiKey = "k", audioBytes = byteArrayOf(9), transport = t)
        assertEquals("done", result.getOrThrow())
        assertTrue(calls >= 3)
        assertEquals("k", t.lastHeaders["Authorization"])
    }

    @Test
    fun `edge validate key succeeds without key`() {
        assertTrue(VoiceGateway.validateKey(VoiceProviderKind.EDGE, transport = FakeTransport { _, _, _ -> VoiceResponse(500, ByteArray(0), "x") }).isSuccess)
    }

    @Test
    fun `cartesia does not offer stt`() {
        val r = VoiceGateway.transcribe(VoiceProviderKind.CARTESIA, apiKey = "k", audioBytes = byteArrayOf(9))
        assertTrue(r.isFailure)
    }

    @Test
    fun `assemblyai does not offer tts`() {
        val r = VoiceGateway.synthesize(VoiceProviderKind.ASSEMBLYAI, apiKey = "k", text = "x")
        assertTrue(r.isFailure)
    }

    @Test
    fun `edge voice catalog parses short names`() {
        val arr = JSONArray().put(JSONObject().put("ShortName", "en-US-AriaNeural")).put(JSONObject().put("ShortName", "sr-RS-SofijaNeural"))
        val voices = EdgeVoiceCatalog.parse(arr.toString())
        assertEquals(listOf("en-US-AriaNeural", "sr-RS-SofijaNeural"), voices)
    }

    @Test
    fun `voice catalog falls back when fetch fails`() {
        val t = FakeTransport { _, _, _ -> VoiceResponse(500, ByteArray(0), "err") }
        assertFalse(EdgeVoiceCatalog.fetch(t).isEmpty())
    }

    @Test
    fun `error messages redact the api key`() {
        assertEquals("bad [redacted] call", VoiceErrors.redact("bad sk-abc call", "sk-abc"))
        assertEquals("no key here", VoiceErrors.redact("no key here", "sk-abc"))
    }
}
