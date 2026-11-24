package com.mobileharness.code.voice

import org.json.JSONArray

/**
 * Microsoft Edge Read Aloud voice catalogue. The full list carries 300+ entries across 140
 * locales; a curated fallback keeps the common ones for offline use until the live list loads.
 */
object EdgeVoiceCatalog {

    /** Fallback ShortNames used only when the live catalogue cannot be fetched. */
    val fallbackVoices: List<String> = listOf(
        "pt-BR-FranciscaNeural", "pt-BR-AntonioNeural", "pt-BR-ThalitaMultilingualNeural",
        "pt-PT-RaquelNeural", "pt-PT-DuarteNeural",
        "en-US-AriaNeural", "en-US-GuyNeural", "en-US-JennyNeural",
        "en-US-AvaNeural", "en-US-AndrewNeural", "en-US-EmmaNeural", "en-US-BrianNeural",
        "en-GB-SoniaNeural", "en-GB-RyanNeural",
        "es-ES-ElviraNeural", "es-ES-AlvaroNeural", "es-MX-DaliaNeural", "es-MX-JorgeNeural",
        "fr-FR-DeniseNeural", "fr-FR-HenriNeural",
        "de-DE-KatjaNeural", "de-DE-ConradNeural",
        "it-IT-ElsaNeural", "it-IT-DiegoNeural",
        "ja-JP-NanamiNeural", "ja-JP-KeitaNeural",
        "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural",
        "hr-HR-GabrijelaNeural", "sr-RS-SofijaNeural", "sv-SE-MattiasNeural",
    )

    /** Parses the catalogue JSON (an array of objects each carrying a `ShortName`). */
    fun parse(body: String): List<String> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val short = o.optString("ShortName").takeIf { it.isNotBlank() } ?: continue
            out += short
        }
        return out.distinct()
    }

    /** Fetches the live catalogue; falls back to [fallbackVoices] on any failure. */
    fun fetch(transport: VoiceTransport = HttpVoiceTransport): List<String> {
        val resp = transport.get(EdgeTtsClient.VOICE_LIST_URL, emptyMap())
        return if (resp.isSuccess) parse(resp.text()).ifEmpty { fallbackVoices } else fallbackVoices
    }

    /** Picks a fallback voice whose locale matches the given BCP-47 language prefix, else the first. */
    fun forLanguage(language: String?): String {
        val prefix = language?.substringBefore('-')?.lowercase().orEmpty()
        return fallbackVoices.firstOrNull { it.lowercase().startsWith(prefix) }
            ?: fallbackVoices.first()
    }
}
