package com.mobileharness.code.mcp

/**
 * Pure decision gate applied before any MCP tool executes. It enforces the server's confirmation
 * mode and the input-size limit. The engine treats [Decision.NeedsConfirmation] as fail-closed in
 * an unattended run (the tool is not executed and the reason is surfaced to the user), while a
 * [Decision.Approved] read-only tool runs immediately.
 */
object McpToolGuard {
    sealed interface Decision {
        object Approved : Decision
        data class NeedsConfirmation(val reason: String) : Decision
        data class Blocked(val reason: String) : Decision
    }

    /**
     * Decides whether [tool] may run for [config] given the raw JSON [argumentsJson] and the
     * current per-call input ceiling. Oversized input is always blocked; a mutating tool (or every
     * tool under ALWAYS_CONFIRM) is held for confirmation, and only NEVER lets everything through.
     */
    fun decide(
        tool: McpTool,
        config: McpServerConfig,
        argumentsJson: String,
        maxInputChars: Int,
    ): Decision {
        if (argumentsJson.length > maxInputChars) {
            return Decision.Blocked(
                "Tool input is ${argumentsJson.length} characters, over the $maxInputChars limit.",
            )
        }
        val mutating = McpPolicy.isMutating(tool.name, tool.description)
        return when (config.confirmMode) {
            McpToolConfirmMode.ALWAYS_CONFIRM ->
                Decision.NeedsConfirmation("This server requires confirmation for every tool.")
            McpToolConfirmMode.MUTATING_ONLY ->
                if (mutating) {
                    Decision.NeedsConfirmation("The tool \"${tool.name}\" may change state and needs approval.")
                } else {
                    Decision.Approved
                }
            McpToolConfirmMode.NEVER -> Decision.Approved
        }
    }
}
