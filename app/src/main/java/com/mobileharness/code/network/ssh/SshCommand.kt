package com.mobileharness.code.network.ssh

/**
 * Pure, fail-closed limits and sanitization for single remote commands executed over SSH.
 *
 * A remote `exec` runs the command through the user's login shell on their own server, so it is
 * inherently "trusted" — but we still bound its size, strip control characters that could
 * confuse the transport, and cap the output so a runaway command cannot exhaust memory or
 * flood the UI. Limits integrate with Eco Mode exactly like the MCP tool policy.
 */
object SshCommand {
    /** Hard cap on a single remote command's length. */
    const val MAX_COMMAND_CHARS = 4096

    /** Maximum output (stdout + stderr) bytes returned to the UI in normal mode. */
    const val MAX_OUTPUT_CHARS = 256 * 1024

    /** Reduced output budget in Eco Mode. */
    const val MAX_OUTPUT_CHARS_ECO = 64 * 1024

    /** Command execution timeout in normal mode. */
    const val TIMEOUT_MILLIS = 60_000L

    /** Reduced execution timeout in Eco Mode. */
    const val TIMEOUT_MILLIS_ECO = 30_000L

    /**
     * Returns a safe, trimmed command or null when it is empty, too long, or contains a NUL or
     * a control character other than tab/newline (which are valid inside a remote command).
     */
    fun sanitize(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_COMMAND_CHARS) return null
        if (trimmed.any { it == '\u0000' || (it.code < 32 && it != '\t' && it != '\n') }) return null
        return trimmed
    }

    fun maxOutputChars(ecoEnabled: Boolean): Int =
        if (ecoEnabled) MAX_OUTPUT_CHARS_ECO else MAX_OUTPUT_CHARS

    fun timeoutMillis(ecoEnabled: Boolean): Long =
        if (ecoEnabled) TIMEOUT_MILLIS_ECO else TIMEOUT_MILLIS

    /** Truncates text to [maxChars] with an ellipsis, without splitting surrogate pairs. */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val cut = maxChars - 1
        val safe = if (cut > 0 && Character.isHighSurrogate(text[cut])) cut - 1 else cut
        return text.substring(0, safe.coerceAtLeast(0)) + "…"
    }
}
