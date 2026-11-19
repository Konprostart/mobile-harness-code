package com.mobileharness.code.device

/**
 * Pure, fail-closed limits and sanitization for privileged commands.
 *
 * Privileged execution can change device state, so the rules here are intentionally strict:
 * empty input, embedded NULs, control characters and oversized payloads are all rejected
 * instead of being passed through to a root/shell interpreter. Limits integrate with Eco Mode
 * exactly like the MCP tool policy and the SSH `exec` policy.
 */
object PrivilegeCommand {
    /** Hard cap on a single shell command string. */
    const val MAX_COMMAND_CHARS = 4096

    /** Hard cap on the number of argv entries (Shizuku `newProcess`). */
    const val MAX_ARG_COUNT = 256

    /** Hard cap on a single argv entry. */
    const val MAX_ARG_CHARS = 1024

    /**
     * Returns a safe, trimmed command string, or null when it is empty, too long, or contains a
     * NUL or a control character other than tab/newline (which are valid inside a shell command).
     */
    fun sanitizeCommand(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_COMMAND_CHARS) return null
        if (trimmed.any { it == '\u0000' || (it.code < 32 && it != '\t' && it != '\n') }) return null
        return trimmed
    }

    /**
     * Returns a sanitized copy of [argv], or null when the list is empty, too long, or any entry
     * is empty, too long, or contains a NUL or control character. Argv entries cannot contain
     * newlines/tabs because they are passed as separate process arguments, never as shell text.
     */
    fun sanitizeArgv(argv: List<String>): List<String>? {
        if (argv.isEmpty() || argv.size > MAX_ARG_COUNT) return null
        val cleaned = ArrayList<String>(argv.size)
        for (arg in argv) {
            if (arg.isEmpty() || arg.length > MAX_ARG_CHARS) return null
            if (arg.any { it == '\u0000' || it.code < 32 }) return null
            cleaned.add(arg)
        }
        return cleaned
    }

    /**
     * Validates a request's payload according to which transport it targets, returning the
     * sanitized command or argv, or null when invalid.
     */
    fun validatePayload(request: PrivilegeRequest): Boolean = when (request.route) {
        PrivilegeRoute.ROOT -> sanitizeCommand(request.command) != null
        PrivilegeRoute.SHIZUKU, PrivilegeRoute.WIRELESS_ADB -> sanitizeArgv(request.argv) != null
    }
}
