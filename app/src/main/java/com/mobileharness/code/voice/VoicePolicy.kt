package com.mobileharness.code.voice

import com.mobileharness.code.network.LocalNetwork
import java.net.URI

/**
 * Pure, fail-closed policy for the voice layer. Mirrors [com.mobileharness.code.mcp.McpPolicy] for
 * URL confinement and adds audio-size and text-length budgets so a misconfigured endpoint or a
 * runaway TTS request cannot exhaust memory or network in the background.
 */
object VoicePolicy {
    /** Maximum audio bytes accepted for a single STT request. */
    const val MAX_AUDIO_BYTES = 10 * 1024 * 1024

    /** Reduced audio budget in Eco Mode. */
    const val MAX_AUDIO_BYTES_ECO = 3 * 1024 * 1024

    /** Maximum characters sent to TTS in one request. */
    const val MAX_TTS_CHARS = 4_000

    const val MAX_TTS_CHARS_ECO = 1_000

    /** HTTPS always allowed; plain HTTP only for loopback/private/link-local hosts. */
    fun isAllowedHttpUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host ?: return false
        return when (scheme) {
            "https" -> host.isNotBlank()
            "http" -> LocalNetwork.isLocalHost(host)
            else -> false
        }
    }

    fun maxAudioBytes(ecoEnabled: Boolean): Int =
        if (ecoEnabled) MAX_AUDIO_BYTES_ECO else MAX_AUDIO_BYTES

    fun maxTtsChars(ecoEnabled: Boolean): Int =
        if (ecoEnabled) MAX_TTS_CHARS_ECO else MAX_TTS_CHARS

    /** Validates a profile before any network call is made. */
    fun validate(profile: VoiceProfile, ecoEnabled: Boolean = false): String? {
        if (profile.kind == VoiceProviderKind.DEVICE) return null
        if (profile.baseUrl.isBlank() && VoiceProviders.defaultBaseUrl(profile.kind).isBlank()) {
            return "No endpoint configured for this voice provider."
        }
        val url = profile.baseUrl.ifBlank { VoiceProviders.defaultBaseUrl(profile.kind) }
        if (!isAllowedHttpUrl(url)) return "The voice endpoint URL is not allowed."
        if (profile.capability == VoiceCapability.TTS && profile.voice.isBlank() && profile.kind != VoiceProviderKind.OPENAI) {
            return "A voice must be selected for text-to-speech."
        }
        return null
    }
}
