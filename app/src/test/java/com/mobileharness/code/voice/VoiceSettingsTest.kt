package com.mobileharness.code.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsTest {

    @Test
    fun `defaults keep device stt and off features`() {
        val s = VoiceSettings()
        assertEquals(VoiceProviderKind.DEVICE, s.sttKind)
        assertFalse(s.autoRead)
        assertFalse(s.liveModeEnabled)
    }

    @Test
    fun `codec round trips`() {
        val original = VoiceSettings(
            sttKind = VoiceProviderKind.GROQ,
            ttsKind = VoiceProviderKind.EDGE,
            sttModel = "whisper-large-v3",
            ttsModel = "",
            ttsVoice = "en-US-AriaNeural",
            voiceLanguage = "sr-RS",
            speed = 1.3f,
            autoRead = true,
            liveModeEnabled = true,
            liveModeAccess = LiveModeAccess.START_TASKS,
            speakReplies = false,
            interruptOnSpeech = true,
            ttsFallbackKind = VoiceProviderKind.OPENAI,
        )
        val decoded = VoiceSettingsCodec.fromJson(VoiceSettingsCodec.toJson(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `malformed json decodes to defaults`() {
        assertEquals(VoiceSettings(), VoiceSettingsCodec.fromJson("not json {"))
        assertEquals(VoiceSettings(), VoiceSettingsCodec.fromJson(""))
    }

    @Test
    fun `unknown enum names fail closed to defaults`() {
        val raw = """{"sttKind":"BOGUS","ttsKind":"NOPE","speed":9.0}"""
        val decoded = VoiceSettingsCodec.fromJson(raw)
        assertEquals(VoiceProviderKind.DEVICE, decoded.sttKind)
        assertEquals(VoiceProviderKind.EDGE, decoded.ttsKind)
        assertEquals(2f, decoded.speed, 0.001f)
    }

    @Test
    fun `speed is clamped`() {
        assertTrue(VoiceSettings().withSpeed(9f).speed == 2f)
        assertTrue(VoiceSettings().withSpeed(0.1f).speed == 0.5f)
    }
}
