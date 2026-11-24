package com.mobileharness.code.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsClientTest {

    @Test
    fun `gec token is pinned for a fixed clock`() {
        assertEquals(
            "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF",
            EdgeTtsClient.gecToken(1_700_000_000_000L),
        )
    }

    @Test
    fun `gec token stable within a 300s window`() {
        val base = 1_700_000_000_000L
        assertEquals(EdgeTtsClient.gecToken(base), EdgeTtsClient.gecToken(base + 60_000L))
    }

    @Test
    fun `gec token changes across a window boundary`() {
        val base = 1_700_000_000_000L
        val nextWindow = base + 300_000L
        assertTrue(EdgeTtsClient.gecToken(base) != EdgeTtsClient.gecToken(nextWindow))
    }

    @Test
    fun `voice tag splits short name into locale and name`() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (pt-BR, FranciscaNeural)",
            EdgeTtsClient.voiceTag("pt-BR-FranciscaNeural"),
        )
    }

    @Test
    fun `escape ssml escapes the five entities`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", EdgeTtsClient.escapeSsml("&<>\"'"))
    }

    @Test
    fun `ssml wraps text in voice and prosody`() {
        val ssml = EdgeTtsClient.ssml("hi", "en-US-AriaNeural", 1.0f)
        assertTrue(ssml.startsWith("<speak"))
        assertTrue(ssml.contains("AriaNeural"))
        assertTrue(ssml.contains("hi"))
    }

    @Test
    fun `session url carries subscription and gec`() {
        val url = EdgeTtsClient.sessionUrl(1_700_000_000_000L)
        assertTrue(url.startsWith("wss://api.msedgeservices.com"))
        assertTrue(url.contains("Sec-MS-GEC="))
        assertTrue(url.contains("Sec-MS-GEC-Version="))
    }

    @Test
    fun `audio from frame strips two byte header length`() {
        val header = byteArrayOf(0x00, 0x05) + "HELLO".toByteArray()
        val audio = EdgeTtsClient.audioFromFrame(header + "AUDIO".toByteArray())
        assertNotNull(audio)
        assertEquals("AUDIO", String(audio!!, Charsets.UTF_8))
    }

    @Test
    fun `audio from frame rejects empty header length`() {
        val frame = byteArrayOf(0x00, 0x00) + "x".toByteArray()
        assertNull(EdgeTtsClient.audioFromFrame(frame))
    }
}
