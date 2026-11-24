package com.mobileharness.code.voice

import java.text.Normalizer

/**
 * Pure voice-command recognition. It maps a recognized utterance to a typed command and, critically,
 * marks anything that could affect real work as needing confirmation. A misheard word must never
 * trigger a destructive operation, so only reversible, voice-session-local commands
 * (pause/resume/mute/repeat/read) run immediately.
 */
enum class VoiceCommandKind {
    NONE,
    STOP_SPEECH,
    PAUSE,
    RESUME,
    READ_REPLY,
    REPEAT,
    STOP_AGENT,
    RUN_TESTS,
    OPEN_PROJECT,
    CHANGE_AGENT,
}

data class VoiceCommand(
    val kind: VoiceCommandKind,
    val requiresConfirmation: Boolean,
)

object VoiceCommandParser {
    /** Multilingual keyword phrases, longest/most specific first. Serbian, Croatian, Bosnian and English. */
    private val rules: List<Pair<VoiceCommandKind, List<String>>> = listOf(
        VoiceCommandKind.STOP_AGENT to listOf(
            "zaustavi agenta", "zaustavi agent", "zaustavi izvrsavanje", "prekini izvrsavanje",
            "stop the agent", "stop agent", "stop execution", "zaustavi zadatak",
        ),
        VoiceCommandKind.RUN_TESTS to listOf(
            "pokreni testove", "pokreni test", "run the tests", "run tests", "pokreni sve testove",
        ),
        VoiceCommandKind.OPEN_PROJECT to listOf(
            "otvori projekat", "otvori projekt", "open the project", "open project",
        ),
        VoiceCommandKind.CHANGE_AGENT to listOf(
            "promeni agenta", "promeni agent", "switch agent", "change agent", "promijeni agenta",
        ),
        VoiceCommandKind.STOP_SPEECH to listOf(
            "prekini govor", "prestani pricati", "stop talking", "stop speech",
            "utihni", "ucuti", "be quiet", "mute",
        ),
        VoiceCommandKind.READ_REPLY to listOf(
            "procitaj odgovor", "read the answer", "read the reply", "read reply", "procitaj",
        ),
        VoiceCommandKind.REPEAT to listOf(
            "ponovi odgovor", "ponovi", "repeat the answer", "repeat", "ponovno procitaj",
        ),
        VoiceCommandKind.PAUSE to listOf(
            "pauziraj", "pauza", "pause",
        ),
        VoiceCommandKind.RESUME to listOf(
            "nastavi", "continue", "resume", "nastavi razgovor",
        ),
    )

    private val confirmationRequired = setOf(
        VoiceCommandKind.STOP_AGENT,
        VoiceCommandKind.RUN_TESTS,
        VoiceCommandKind.OPEN_PROJECT,
        VoiceCommandKind.CHANGE_AGENT,
    )

    /** Lowercases, strips diacritics and collapses whitespace so "Pročitaj" matches "procitaj". */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val stripped = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            val type = Character.getType(ch)
            if (type != Character.NON_SPACING_MARK.toInt() && type != Character.COMBINING_SPACING_MARK.toInt()) {
                stripped.append(ch)
            }
        }
        return stripped.toString().lowercase().replace(Regex("\\s+"), " ").trim()
    }

    fun parse(text: String): VoiceCommand {
        val t = normalize(text)
        if (t.isEmpty()) return VoiceCommand(VoiceCommandKind.NONE, false)
        for (rule in rules) {
            val kind = rule.first
            val phrases = rule.second
            if (phrases.any { t.contains(it) }) {
                return VoiceCommand(kind, kind in confirmationRequired)
            }
        }
        return VoiceCommand(VoiceCommandKind.NONE, false)
    }

    fun requiresConfirmation(kind: VoiceCommandKind): Boolean = kind in confirmationRequired
}
