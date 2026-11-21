package com.mobileharness.code.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {

    @Test
    fun `https endpoint allowed`() {
        assertTrue(VoicePolicy.isAllowedHttpUrl("https://api.openai.com/v1"))
    }

    @Test
    fun `plain http remote endpoint rejected`() {
        assertTrue(!VoicePolicy.isAllowedHttpUrl("http://api.openai.com/v1"))
    }

    @Test
    fun `plain http localhost allowed`() {
        assertTrue(VoicePolicy.isAllowedHttpUrl("http://127.0.0.1:8000/v1"))
    }

    @Test
    fun `validate rejects blank custom endpoint`() {
        val profile = VoiceProfile(kind = VoiceProviderKind.CUSTOM, capability = VoiceCapability.STT)
        assertNotNull(VoicePolicy.validate(profile))
    }

    @Test
    fun `validate passes device stt without key`() {
        assertNull(VoicePolicy.validate(VoiceProfile(kind = VoiceProviderKind.DEVICE)))
    }

    @Test
    fun `validate requires voice for tts on openai`() {
        val profile = VoiceProfile(kind = VoiceProviderKind.OPENAI, baseUrl = "https://api.openai.com/v1", capability = VoiceCapability.TTS)
        assertNull(VoicePolicy.validate(profile))
    }

    @Test
    fun `budgets shrink in eco mode`() {
        assertTrue(VoicePolicy.maxAudioBytes(true) < VoicePolicy.maxAudioBytes(false))
        assertTrue(VoicePolicy.maxTtsChars(true) < VoicePolicy.maxTtsChars(false))
        assertEquals(VoicePolicy.MAX_AUDIO_BYTES_ECO, VoicePolicy.maxAudioBytes(true))
    }
}
