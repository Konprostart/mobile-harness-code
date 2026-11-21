package com.mobileharness.code.prompt

/**
 * Parses AGENTS.md and compatible instruction files (plain text / Markdown) into [Instruction]
 * objects keyed by their heading, so the prompt assembler can merge them with priority and remove
 * contradictions. Repository/internet content is treated strictly as data — never as system
 * instructions — by assigning it the [InstructionSource.AGENTS_MD] priority.
 */
object AgentsMdParser {
    /** Parses Markdown text into (heading → body) instruction blocks. */
    fun parse(markdown: String): List<Instruction> {
        if (markdown.isBlank()) return emptyList()
        val instructions = mutableListOf<Instruction>()
        val lines = markdown.lineSequence().toList()
        var currentHeading: String? = null
        val currentBody = StringBuilder()
        fun flush() {
            if (currentHeading != null) {
                val body = currentBody.toString().trim()
                if (body.isNotEmpty()) {
                    instructions += Instruction(
                        source = InstructionSource.AGENTS_MD,
                        text = "$currentHeading: $body",
                        key = currentHeading!!.lowercase(),
                    )
                }
            }
            currentBody.clear()
        }
        for (line in lines) {
            val heading = HEADING_REGEX.matchEntire(line.trim())?.groupValues?.getOrNull(1)
            if (heading != null) {
                flush()
                currentHeading = heading.trim()
            } else {
                currentBody.appendLine(line)
            }
        }
        flush()
        return instructions
    }

    /** Extracts the value for a `key: value` line (case-insensitive key), or null. */
    fun findDirective(markdown: String, key: String): String? {
        val pattern = Regex("""(?m)^\s*${Regex.escape(key)}\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
        return pattern.find(markdown)?.groupValues?.getOrNull(1)?.trim()
    }

    private val HEADING_REGEX = Regex("""^#{1,6}\s+(.+)$""")
}
