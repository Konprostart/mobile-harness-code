package com.mobileharness.code.runtime

import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Crash-surviving state of a single agent run (DeepSeek Harness, Claude Code or Antigravity).
 *
 * This is deliberately a *journal*, not a live handle: a record describes what was running and
 * how far it got, so that after the app process (and therefore the agent child process) is killed
 * the user can be shown exactly what was interrupted and given a safe manual resume. It never
 * attempts to resurrect a dead PTY/PRoot process on its own — recovery stays fail-closed in
 * [RuntimeRecoveryPolicy].
 *
 * The store only keeps metadata: no API keys, tokens or secrets are written. [prompt] is a
 * truncated echo of the task and [lastOutput]/[lastError] are bounded tails.
 */
enum class RuntimeSessionState { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

data class RuntimeSessionRecord(
    val sessionId: String,
    val projectId: String,
    val agentStableId: String,
    val agentTitle: String,
    val model: String,
    val providerId: String,
    val prompt: String,
    val historyMessageCount: Int,
    val startedAtMillis: Long,
    val lastUpdatedAtMillis: Long,
    val state: RuntimeSessionState,
    val lastOutput: String,
    val lastError: String,
    val exitCode: Int?,
    val toolsExecuted: Boolean,
    val mutatingToolsExecuted: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("projectId", projectId)
        .put("agentStableId", agentStableId)
        .put("agentTitle", agentTitle)
        .put("model", model)
        .put("providerId", providerId)
        .put("prompt", prompt)
        .put("historyMessageCount", historyMessageCount)
        .put("startedAtMillis", startedAtMillis)
        .put("lastUpdatedAtMillis", lastUpdatedAtMillis)
        .put("state", state.name)
        .put("lastOutput", lastOutput)
        .put("lastError", lastError)
        .put("exitCode", exitCode ?: JSONObject.NULL)
        .put("toolsExecuted", toolsExecuted)
        .put("mutatingToolsExecuted", mutatingToolsExecuted)

    companion object {
        fun fromJson(json: JSONObject): RuntimeSessionRecord = RuntimeSessionRecord(
            sessionId = json.optString("sessionId").ifBlank { UUID.randomUUID().toString() },
            projectId = json.optString("projectId"),
            agentStableId = json.optString("agentStableId"),
            agentTitle = json.optString("agentTitle"),
            model = json.optString("model"),
            providerId = json.optString("providerId"),
            prompt = json.optString("prompt"),
            historyMessageCount = json.optInt("historyMessageCount"),
            startedAtMillis = json.optLong("startedAtMillis"),
            lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis"),
            state = runCatching { RuntimeSessionState.valueOf(json.optString("state")) }
                .getOrDefault(RuntimeSessionState.INTERRUPTED),
            lastOutput = json.optString("lastOutput"),
            lastError = json.optString("lastError"),
            exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode"),
            toolsExecuted = json.optBoolean("toolsExecuted"),
            mutatingToolsExecuted = json.optBoolean("mutatingToolsExecuted"),
        )
    }
}

/**
 * File-backed journal of agent sessions. One JSON file per session under
 * `filesDir/runtime-sessions/`; write-then-rename keeps a torn write from corrupting the store.
 * Thread-safe: a single monitor guards all read-modify-write cycles so concurrent bridges
 * (each running on its own IO dispatcher) never clobber one another.
 */
class RuntimeSessionStore(private val filesDir: File) {
    private val directory: File = File(filesDir, "runtime-sessions").apply { mkdirs() }
    private val monitor = Any()

    fun recordRunning(
        sessionId: String,
        projectId: String,
        agentStableId: String,
        agentTitle: String,
        model: String,
        providerId: String,
        prompt: String,
        historyMessageCount: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ) = write(
        RuntimeSessionRecord(
            sessionId = sessionId,
            projectId = projectId,
            agentStableId = agentStableId,
            agentTitle = agentTitle,
            model = model,
            providerId = providerId,
            prompt = prompt.take(MAX_PROMPT_CHARS),
            historyMessageCount = historyMessageCount,
            startedAtMillis = nowMillis,
            lastUpdatedAtMillis = nowMillis,
            state = RuntimeSessionState.RUNNING,
            lastOutput = "",
            lastError = "",
            exitCode = null,
            toolsExecuted = false,
            mutatingToolsExecuted = false,
        ),
    )

    /** Update a running record's progress without rewriting fields the bridge does not own. */
    fun updateRunning(
        sessionId: String,
        lastOutput: String = "",
        toolsExecuted: Boolean? = null,
        mutatingToolsExecuted: Boolean? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(monitor) {
            val current = read(sessionId) ?: return
            if (current.state != RuntimeSessionState.RUNNING) return
            write(
                current.copy(
                    lastOutput = if (lastOutput.isBlank()) current.lastOutput else lastOutput.take(MAX_OUTPUT_CHARS),
                    toolsExecuted = toolsExecuted ?: current.toolsExecuted,
                    mutatingToolsExecuted = mutatingToolsExecuted ?: current.mutatingToolsExecuted,
                    lastUpdatedAtMillis = nowMillis,
                ),
            )
        }
    }

    fun recordTerminal(
        sessionId: String,
        state: RuntimeSessionState,
        exitCode: Int?,
        lastError: String = "",
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(monitor) {
            val current = read(sessionId) ?: return
            if (current.state != RuntimeSessionState.RUNNING) return
            write(
                current.copy(
                    state = state,
                    exitCode = exitCode,
                    lastError = lastError.take(MAX_OUTPUT_CHARS),
                    lastUpdatedAtMillis = nowMillis,
                ),
            )
        }
    }

    /** Latest running session across all projects, or null when nothing is journaled as running. */
    fun activeSession(): RuntimeSessionRecord? = synchronized(monitor) {
        listRecords().filter { it.state == RuntimeSessionState.RUNNING }.maxByOrNull { it.lastUpdatedAtMillis }
    }

    /** Running sessions, newest first. Used to detect tasks that were interrupted by process death. */
    fun activeSessions(): List<RuntimeSessionRecord> = synchronized(monitor) {
        listRecords().filter { it.state == RuntimeSessionState.RUNNING }.sortedByDescending { it.lastUpdatedAtMillis }
    }

    /**
     * Turn every RUNNING record into INTERRUPTED. Call once on app start (before any new session
     * is launched): any process that was still running at that moment is, by definition, gone,
     * because the journal lives in the same process as the agent child it describes.
     */
    fun markAllInterrupted(nowMillis: Long = System.currentTimeMillis()): List<RuntimeSessionRecord> =
        synchronized(monitor) {
            listRecords()
                .filter { it.state == RuntimeSessionState.RUNNING }
                .map { record ->
                    record.copy(state = RuntimeSessionState.INTERRUPTED, lastUpdatedAtMillis = nowMillis).also(::write)
                }
        }

    fun read(sessionId: String): RuntimeSessionRecord? = synchronized(monitor) {
        val file = fileFor(sessionId)
        if (!file.isFile) return@synchronized null
        runCatching { RuntimeSessionRecord.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    /** Most recent terminal records, newest first, capped at [limit]. */
    fun recent(limit: Int = 20): List<RuntimeSessionRecord> = synchronized(monitor) {
        listRecords()
            .filter { it.state != RuntimeSessionState.RUNNING }
            .sortedByDescending { it.lastUpdatedAtMillis }
            .take(limit)
    }

    fun clearProject(projectId: String) = synchronized(monitor) {
        listRecords().filter { it.projectId == projectId }.forEach { fileFor(it.sessionId).delete() }
    }

    fun clear() = synchronized(monitor) {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun write(record: RuntimeSessionRecord) {
        val target = fileFor(record.sessionId)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(record.toJson().toString())
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private fun listRecords(): List<RuntimeSessionRecord> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file -> runCatching { RuntimeSessionRecord.fromJson(JSONObject(file.readText())) }.getOrNull() }

    private fun fileFor(sessionId: String): File = File(directory, "$sessionId.json")

    companion object {
        private const val MAX_PROMPT_CHARS = 4_000
        private const val MAX_OUTPUT_CHARS = 2_000
    }
}
