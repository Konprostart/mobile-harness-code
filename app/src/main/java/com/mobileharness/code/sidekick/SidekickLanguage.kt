package com.mobileharness.code.sidekick

/**
 * Response-language policy for the Sidekick assistant. This is deliberately independent of
 * [SpeechLanguage], which resolves a *voice recognition* tag for the system recognizer: here we
 * map the user's persisted choice to a system-prompt instruction so the assistant *answers* in the
 * requested language, and we delegate to the model (with a conservative hint) in Auto mode.
 *
 * Everything is pure and deterministic so the mapping can be unit-tested without Android.
 */
object SidekickLanguage {
    const val AUTO = "auto"

    /** First-class response languages, matching the voice-input tags exactly. */
    val SUPPORTED = listOf("sr-RS", "en-US", "en-GB", "sv-SE")

    private val SERBIAN_DIACRITICS = "ćčšžđĆČŠŽĐ"
    private val SWEDISH_DIACRITICS = "åäöÅÄÖ"
    private val SERBIAN_WORDS = setOf(
        "što", "kako", "zašto", "molim", "hvala", "pomozi", "napiši", "izgradi", "greška",
        "treba", "radi", "pitanje", "odgovor", "jezik", "ako", "ali", "sve", "samo", "šta",
        "dobro", "zdravo", "možeš", "želim",
    )
    private val SWEDISH_WORDS = setOf(
        "och", "det", "är", "att", "inte", "hjälp", "skriv", "bygg", "fel", "tack",
        "snälla", "hur", "vad", "varför", "kan", "du", "hej", "vill",
    )

    /**
     * Canonicalizes a persisted choice. Blank, `null` or `"auto"` (any case) normalizes to
     * [AUTO]; a recognized concrete tag is normalized through [SpeechLanguage.normalize]; anything
     * else falls back to [AUTO]. This is the single source of truth that prevents an unrecognized
     * or corrupt stored value from leaking into the prompt.
     */
    fun normalizeChoice(choice: String?): String {
        val raw = choice?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals(AUTO, ignoreCase = true)) return AUTO
        val normalized = SpeechLanguage.normalize(raw)
        return if (normalized in SUPPORTED) normalized else AUTO
    }

    /**
     * System-prompt instruction for the assistant. A concrete language forces replies in that
     * language regardless of the user's wording; [AUTO] delegates to the model but, when
     * [userText] lets us detect a language confidently, adds a hint so the assistant answers in
     * the detected language.
     */
    fun responseInstruction(choice: String?, userText: String? = null): String {
        return when (normalizeChoice(choice)) {
            "sr-RS" -> "Language: always reply in Serbian (Latin script), regardless of the language of the question."
            "en-US", "en-GB" -> "Language: always reply in English, regardless of the language of the question."
            "sv-SE" -> "Language: always reply in Swedish, regardless of the language of the question."
            else -> when (userText?.let { detectLanguageTag(it) }) {
                "sr-RS" -> "Language: the user appears to be writing in Serbian — reply in Serbian (Latin script). If you detect a different language, reply in that language instead."
                "sv-SE" -> "Language: the user appears to be writing in Swedish — reply in Swedish. If you detect a different language, reply in that language instead."
                else -> "Language: reply in the same language the user wrote in."
            }
        }
    }

    /**
     * Conservative, deterministic language detection used only as a hint in Auto mode. It returns
     * a supported tag only on strong signals (Serbian/Swedish diacritics or distinctive words) and
     * otherwise `null` so a wrong guess can never override the model's own judgment.
     */
    fun detectLanguageTag(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it in SERBIAN_DIACRITICS }) return "sr-RS"
        if (trimmed.any { it in SWEDISH_DIACRITICS }) return "sv-SE"
        val words = trimmed.lowercase()
            .split(Regex("[\\s,.;:!?()\\[\\]{}<>\"']+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (words.any { it in SERBIAN_WORDS }) return "sr-RS"
        if (words.any { it in SWEDISH_WORDS }) return "sv-SE"
        return null
    }
}
