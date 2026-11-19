package com.mobileharness.code.device

/** A single privileged-shell audit entry, shown in Developer Tools. Never contains secrets. */
data class PrivilegeLogEntry(
    val timestampMillis: Long,
    val route: PrivilegeRoute,
    val stage: PrivilegeStage,
    val detail: String? = null,
)

enum class PrivilegeStage {
    DETECTED,
    OPT_IN_CHANGED,
    PERMISSION_REQUESTED,
    PERMISSION_GRANTED,
    PERMISSION_DENIED,
    EXEC,
    DENIED,
    TIMEOUT,
    ERROR,
    SUCCESS,
}

/**
 * In-memory ring buffer of privileged-shell events, mirroring the SSH/MCP log pattern. Process
 * scoped and cleared on restart; entries never include passwords, tokens or command bodies that
 * could embed sensitive values.
 */
object PrivilegeEventLog {
    private const val MAX_ENTRIES = 100
    private val entries = ArrayDeque<PrivilegeLogEntry>()

    @Synchronized
    fun record(route: PrivilegeRoute, stage: PrivilegeStage, detail: String? = null) {
        entries.addLast(PrivilegeLogEntry(System.currentTimeMillis(), route, stage, detail))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<PrivilegeLogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
