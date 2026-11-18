package com.mobileharness.code.sidekick

/**
 * Pure language-tag resolution for the voice input button. The user can pick a language in the
 * input bar; when they leave it on "Auto" the resolver prefers the languages the assistant is
 * tuned for (Serbian, English, Swedish) and otherwise falls back to the first device locale.
 */
object SpeechLanguage {
    /** Languages the assistant explicitly supports first-class, in preference order. */
    val PRIORITY_TAGS = listOf("sr-RS", "en-US", "en-GB", "sv-SE")

    const val DEFAULT = "en-US"
    const val AUTO = "auto"

    /** Normalizes "sr_RS", "sr rs", "SR-RS" → "sr-RS"; returns "" for anything unusable. */
    fun normalize(tag: String): String {
        val t = tag.trim()
        if (t.isEmpty()) return ""
        val parts = t.split('_', '-', ' ')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return ""
        val language = parts[0].lowercase()
        val region = parts[1].uppercase()
        return "$language-$region"
    }

    /**
     * Resolves the language tag to send to the system recognizer. A non-blank, non-auto preferred
     * value always wins; otherwise the highest-priority [PRIORITY_TAGS] entry present in
     * [systemLocales] wins, then the first valid system locale, then [DEFAULT].
     */
    fun resolve(preferred: String?, systemLocales: List<String>): String {
        val p = preferred?.let(::normalize).orEmpty()
        if (p.isNotEmpty()) return p
        val normalized = systemLocales.map(::normalize).filter { it.isNotEmpty() }
        val present = normalized.toSet()
        return PRIORITY_TAGS.firstOrNull { it in present } ?: normalized.firstOrNull() ?: DEFAULT
    }
}
