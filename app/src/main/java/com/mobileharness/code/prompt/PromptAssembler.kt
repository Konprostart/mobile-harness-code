package com.mobileharness.code.prompt

import java.util.UUID

/**
 * Central prompt assembler. Combines a task with project/workspace context, instructions and memory
 * into an [AssembledPrompt], removing contradictions (by instruction priority) and enforcing a token
 * budget. Pure JVM and deterministic except for the generated version id, so it is unit-testable.
 */
object PromptAssembler {
    /** Approximate tokens per char: ~4 chars/token for English prose. */
    const val CHARS_PER_TOKEN = 4

    fun assemble(context: PromptContext, instructions: List<Instruction>): AssembledPrompt {
        val deduped = dedupe(instructions)
        val system = buildSystem(deduped)
        val developer = buildDeveloper(context, deduped)
        val task = buildTask(context)
        return AssembledPrompt(
            system = system,
            developer = developer,
            task = task,
            instructions = deduped,
            versionId = UUID.randomUUID().toString(),
        )
    }

    /**
     * Removes contradictions: instructions sharing a [Instruction.key] collapse to the highest
     * priority (lowest number). Instructions without a key are kept in priority order.
     */
    fun dedupe(instructions: List<Instruction>): List<Instruction> {
        val byKey = LinkedHashMap<String, Instruction>()
        val unkeyed = mutableListOf<Instruction>()
        for (ins in instructions.sortedBy { it.source.priority }) {
            if (ins.key == null) {
                unkeyed += ins
            } else {
                val existing = byKey[ins.key]
                if (existing == null || ins.source.priority < existing.source.priority) {
                    byKey[ins.key] = ins
                }
            }
        }
        return (byKey.values + unkeyed).sortedBy { it.source.priority }
    }

    /** True if two instructions with the same key/text contradict (case-insensitive). */
    fun contradicts(a: Instruction, b: Instruction): Boolean =
        a.key != null && a.key == b.key && !a.text.equals(b.text, ignoreCase = true)

    fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    fun fitBudget(text: String, maxTokens: Int): String {
        val maxChars = maxTokens * CHARS_PER_TOKEN
        if (text.length <= maxChars) return text
        return text.take(maxChars - 20).trimEnd() + "\n…[truncated for token budget]"
    }

    private fun buildSystem(instructions: List<Instruction>): String {
        val mandatory = instructions.filter { it.source == InstructionSource.SYSTEM || it.source == InstructionSource.SECURITY }
        return mandatory.joinToString("\n\n") { it.text }
    }

    private fun buildDeveloper(context: PromptContext, instructions: List<Instruction>): String {
        val sb = StringBuilder()
        if (context.projectId != null) sb.appendLine("Project: ${context.projectId}")
        if (context.workspacePath != null) sb.appendLine("Workspace: ${context.workspacePath}")
        if (context.repoSummary.isNotBlank()) {
            sb.appendLine("Repository summary:")
            sb.appendLine(context.repoSummary)
        }
        val guidance = instructions.filter {
            it.source !in setOf(InstructionSource.SYSTEM, InstructionSource.SECURITY, InstructionSource.TASK, InstructionSource.USER)
        }
        if (guidance.isNotEmpty()) {
            sb.appendLine("Instructions:")
            guidance.forEach { sb.appendLine("- ${it.text}") }
        }
        if (context.memory.isNotBlank()) {
            sb.appendLine("Relevant memory:")
            sb.appendLine(context.memory)
        }
        return sb.toString().trimEnd()
    }

    private fun buildTask(context: PromptContext): String = context.task.trim()
}
