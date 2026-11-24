package com.mobileharness.code.voice

/**
 * TTS engines cap how much text one request may carry. Split on sentence and line boundaries so a
 * long reply is spoken without cutting a word or a sentence in half.
 *
 * Ported from AndroidHarness 4.0 (MIT, Copyright 2026 Sanuu7) — see NOTICE.
 */
object TtsTextChunker {
    private val sentenceBreak = Regex("(?<=[.!?。！？])(?=\\s)")

    fun chunk(text: String, maxChars: Int): List<String> {
        val limit = maxChars.coerceAtLeast(120)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            current.toString().trim()
                .takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
                ?.let(chunks::add)
            current.setLength(0)
        }

        fun append(piece: String) {
            val separator = if (current.isEmpty()) 0 else 1
            if (current.length + separator + piece.length <= limit) {
                if (separator == 1) current.append(' ')
                current.append(piece)
            } else {
                flush()
                current.append(piece)
            }
        }

        for (paragraph in text.replace("\r\n", "\n").split(Regex("\\n+"))) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            for (sentence in sentenceBreak.split(trimmed)) {
                var rest = sentence.trim()
                while (rest.isNotEmpty()) {
                    val room = limit - current.length - if (current.isEmpty()) 0 else 1
                    when {
                        rest.length <= room -> {
                            append(rest)
                            rest = ""
                        }
                        room < limit / 2 -> flush()
                        else -> {
                            val cut = cutPoint(rest, room)
                            append(rest.substring(0, cut).trim())
                            flush()
                            rest = rest.substring(cut).trim()
                        }
                    }
                }
            }
            flush()
        }
        flush()
        return chunks
    }

    private fun cutPoint(text: String, limit: Int): Int {
        if (text.length <= limit) return text.length
        val boundary = text.lastIndexOf(' ', startIndex = limit - 1)
        return if (boundary >= limit / 2) boundary else limit
    }
}
