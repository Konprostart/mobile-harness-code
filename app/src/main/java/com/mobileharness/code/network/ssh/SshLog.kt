package com.mobileharness.code.network.ssh

/** A single SSH audit entry, shown in Developer Tools. Never contains credentials or key material. */
data class SshLogEntry(
    val timestampMillis: Long,
    val profileName: String,
    val stage: SshStage,
    val detail: String? = null,
)

enum class SshStage {
    CONNECT,
    TOFU_ACCEPT,
    TOFU_MISMATCH,
    AUTH,
    EXEC,
    TUNNEL_START,
    TUNNEL_STOP,
    SHELL_OPEN,
    SHELL_CLOSE,
    ERROR,
    SUCCESS,
}

/**
 * In-memory ring buffer of SSH events, mirrored from the MCP tool log so Developer Tools share
 * one presentation pattern. Process-scoped and cleared on restart; entries never include
 * passwords, private keys, passphrases or bearer tokens.
 */
object SshLog {
    private const val MAX_ENTRIES = 100
    private val entries = ArrayDeque<SshLogEntry>()

    @Synchronized
    fun record(entry: SshLogEntry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<SshLogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
