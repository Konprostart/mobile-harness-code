package com.mobileharness.code.runtime

/**
 * Status of the last tool operation when a session is interrupted. [IN_FLIGHT] means the tool was
 * started but its result was never observed, so a new model must verify rather than blindly
 * re-run it — the same fail-closed principle as [DSH_CONTINUATION_INSTRUCTION].
 */
enum class LastToolStatus { DONE, IN_FLIGHT }

/** Last tool fact carried across a provider/model handoff. [resultDigest] is display-only. */
data class LastTool(
    val name: String,
    val status: LastToolStatus,
    val resultDigest: String = "",
)

/**
 * Provider-agnostic handoff state given to the next model when a task is resumed after an
 * interruption or rotation. It is plain text/JSON-able data, so it transfers identically between
 * DeepSeek, Anthropic, OpenRouter and any other provider — none of which share session state.
 *
 * It deliberately never carries an API key: [recentMessages] (the raw transcript) travels through
 * the existing [com.mobileharness.code.model.RecoveryAction.history] channel instead of being
 * duplicated here, so this pack only holds the *compressed* handoff facts that save tokens.
 */
data class ContinuationPack(
    val task: String,
    val summary: String = "",
    val touchedFiles: List<String> = emptyList(),
    val gitDiffStat: String = "",
    val lastTool: LastTool? = null,
)

object ContinuationPackBuilder {
    /** Builds a handoff pack for an interrupted turn; [toolsExecuted] drives the verify-don't-replay guard. */
    fun forInterruptedTask(
        task: String,
        toolsExecuted: Boolean,
        touchedFiles: List<String> = emptyList(),
        gitDiffStat: String = "",
        summary: String = "",
    ): ContinuationPack = ContinuationPack(
        task = task,
        summary = summary,
        touchedFiles = touchedFiles,
        gitDiffStat = gitDiffStat,
        lastTool = if (toolsExecuted) LastTool("tool", LastToolStatus.IN_FLIGHT) else null,
    )
}

/**
 * Renders the handoff as a stable, token-lean instruction block that is prepended to the resumed
 * prompt. It only grows with facts that are actually present, so a simple reconnect costs a few
 * lines while a mid-tool interruption adds the verify guard.
 */
fun ContinuationPack.toInstruction(): String = buildString {
    appendLine("<continuation_pack>")
    appendLine("The previous attempt was interrupted. Continue the same task from where it stopped.")
    appendLine("Do not repeat operations that already have results.")
    lastTool?.let { tool ->
        appendLine("Last tool: ${tool.name} (${tool.status.name.lowercase()}).")
        if (tool.status == LastToolStatus.IN_FLIGHT) {
            appendLine("Before re-running it, inspect the workspace and verify whether it already happened.")
        }
    }
    if (touchedFiles.isNotEmpty()) {
        appendLine("Files touched so far:")
        touchedFiles.forEach { appendLine("- $it") }
    }
    if (gitDiffStat.isNotBlank()) {
        appendLine("git diff --stat:")
        appendLine(gitDiffStat)
    }
    if (summary.isNotBlank()) {
        appendLine("Summary of earlier context:")
        appendLine(summary)
    }
    appendLine("</continuation_pack>")
}
