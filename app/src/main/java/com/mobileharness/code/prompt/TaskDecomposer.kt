package com.mobileharness.code.prompt

/**
 * Task decomposition: splits a large task into ordered sub-tasks, each with a self-contained prompt
 * so a sub-agent can run it independently. Pure and deterministic (no model call) — it uses explicit
 * delimiters the caller inserts, so the split is predictable and testable.
 */
object TaskDecomposer {
    /** Splits [task] on a `---` line or a numbered-list header; falls back to a single task. */
    fun decompose(task: String, maxSubTasks: Int = 8): List<SubTask> {
        val parts = splitIntoParts(task)
        if (parts.size <= 1) {
            return listOf(SubTask(id = "0", title = firstLine(task), prompt = task.trim()))
        }
        return parts.take(maxSubTasks).mapIndexed { index, part ->
            val title = firstLine(part)
            SubTask(
                id = index.toString(),
                title = title,
                prompt = part.trim(),
                dependsOn = if (index == 0) emptyList() else listOf((index - 1).toString()),
            )
        }
    }

    fun splitIntoParts(task: String): List<String> {
        val trimmed = task.trim()
        val delimiter = Regex("""(?m)^---+\s*$""")
        val byRule = delimiter.split(trimmed).map(String::trim).filter { it.isNotEmpty() }
        if (byRule.size > 1) return byRule

        // Numbered or bullet list of sizable items.
        val listItems = Regex("""(?m)^\s*(\d+[.)]|[-*])\s+(?=\S)""").findAll(trimmed).toList()
        if (listItems.size > 1) {
            val items = mutableListOf<String>()
            var current = StringBuilder()
            var index = 0
            for ((i, match) in listItems.withIndex()) {
                val start = match.range.first
                if (i > 0) {
                    items += current.toString().trim()
                    current = StringBuilder()
                }
                val end = if (i + 1 < listItems.size) listItems[i + 1].range.first else trimmed.length
                current.append(trimmed.substring(start, end))
                index = start
            }
            items += current.toString().trim()
            return items.filter { it.isNotBlank() }
        }
        return listOf(trimmed)
    }

    fun firstLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (line.length <= 80) line else line.take(77).trimEnd() + "…"
    }
}
