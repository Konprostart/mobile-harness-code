package com.mobileharness.code.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.mobileharness.code.R
import com.mobileharness.code.devtools.DebugLog
import com.mobileharness.code.model.AgentKind
import com.mobileharness.code.model.ChatMessage
import com.mobileharness.code.model.ChangeItem
import com.mobileharness.code.model.ProjectKind
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.model.RecoveryDecision
import com.mobileharness.code.model.RecoveryFailureInfo
import com.mobileharness.code.model.RuntimeEvent
import com.mobileharness.code.model.RuntimeRecoveryContext
import com.mobileharness.code.model.RuntimeRecoveryProfile
import com.mobileharness.code.model.ToolRequest
import com.mobileharness.code.permission.DestructiveOpClassifier
import com.mobileharness.code.permission.PermissionController
import com.mobileharness.code.permission.PermissionDecision
import com.mobileharness.code.permission.PermissionRequest
import com.mobileharness.code.permission.PermissionSubject
import com.mobileharness.code.token.TokenUsage
import com.mobileharness.code.token.UsageSource
import com.mobileharness.code.ui.AppStrings
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Goose coding/tool agent, integrated through its Agent Client Protocol (`goose acp`) subprocess.
 *
 * The bridge spawns `goose acp --with-builtin developer` inside the PRoot runtime, drives the
 * JSON-RPC 2.0 handshake (`initialize` → `session/new` → `session/prompt`), streams the
 * `session/update` notifications into [RuntimeEvent]s, and routes every server-initiated
 * `session/request_permission` through the app's unified [PermissionController] approval system
 * (fail-closed: any error denies). Credentials are injected via `GOOSE_PROVIDER__API_KEY` env and
 * are never written to a plaintext config file; `GOOSE_MODE=approve` guarantees every tool call is
 * gated instead of auto-executed.
 */
class GooseRuntimeBridge(
    private val context: Context,
    private val sessionStore: RuntimeSessionStore = RuntimeSessionStore(context.filesDir),
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus

    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeProviderId: String? = null
    @Volatile private var activeModel: String? = null
    @Volatile private var liveSession: LiveSession? = null
    @Volatile private var gooseSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var sawToolCall: Boolean = false
    @Volatile private var sawMutatingTool: Boolean = false
    @Volatile private var lastSessionId: String? = null
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    private val pendingPermissions = ConcurrentHashMap<String, PendingPermission>()

    override val recoveryProfile: RuntimeRecoveryProfile = GOOSE_RECOVERY_PROFILE

    override fun recoveryContext(sessionId: String, projectId: String): RuntimeRecoveryContext? {
        val isCurrent = activeSessionId == sessionId
        val isLast = lastSessionId == sessionId
        if (!isCurrent && !isLast) return null
        return RuntimeRecoveryContext(
            sessionId = sessionId,
            inPlaceContinuationAvailable = false,
            persistedHandle = null,
            toolsExecuted = sawToolCall,
            mutatingToolsExecuted = sawMutatingTool,
            interruptedToolOutcomeKnown = false,
            checkpointAvailable = false,
            userStopped = userStopRequested,
        )
    }

    override suspend fun recover(action: RecoveryAction, decision: RecoveryDecision): Boolean {
        if (decision != RecoveryDecision.RETRY_NEW_SESSION_SAFE) return false
        // Defense in depth: never auto-restart a turn that already ran tools.
        val context = recoveryContext(action.sessionId, action.projectId) ?: return false
        if (context.toolsExecuted) return false
        startSession(
            action.projectId,
            action.projectSlug,
            action.projectKind,
            action.originalPrompt,
            action.history,
            action.provider,
        )
        return true
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        lastSessionId = sessionId
        sawToolCall = false
        sawMutatingTool = false
        activeSessionId = sessionId
        activeProviderId = provider.providerId.ifBlank { provider.kind.name }
        activeModel = provider.model
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        gooseSessionId = null
        pendingPermissions.clear()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        sessionStore.recordRunning(
            sessionId = sessionId,
            projectId = projectId,
            agentStableId = "goose",
            agentTitle = "Goose",
            model = provider.model,
            providerId = provider.providerId,
            prompt = prompt,
            historyMessageCount = conversationHistory.size,
        )
        pushForegroundProgress(R.string.rt_bridge_starting, "Goose")

        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank()) {
            val message = "No API key is saved for ${provider.kind.title}."
            sessionStore.recordTerminal(sessionId, RuntimeSessionState.FAILED, exitCode = null, lastError = message)
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
            return@withContext sessionId
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            val message = "Claude subscription login is not supported by Goose. Pick a key-based provider in Settings."
            sessionStore.recordTerminal(sessionId, RuntimeSessionState.FAILED, exitCode = null, lastError = message)
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        runCatching { running.destroy() }
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(AgentKind.GOOSE)) {
                "Goose is not installed. Open Settings → Coding agent to install it."
            }
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val environment = linkedMapOf(
                "DISABLE_AUTOUPDATER" to "1",
                "DISABLE_TELEMETRY" to "1",
                "DISABLE_ERROR_REPORTING" to "1",
            ).apply { putAll(gooseProviderEnvironment(provider, secret)) }

            val command = listOf(GooseAcpCommandPath, "acp", "--with-builtin", "developer")
            DebugLog.d("GooseBridge", "Launching command: $command")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            coroutineScope {
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
                liveSession = LiveSession(sessionId, process, writer)
                val outcome = runAcpTurn(sessionId, process, nativeProcess, writer, guestWorkspacePath, contextPrompt)
                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                    acceptLastChanges(projectId)
                }
                if (outcome.failure == null) {
                    sessionStore.updateRunning(sessionId, toolsExecuted = sawToolCall, mutatingToolsExecuted = sawMutatingTool)
                    sessionStore.recordTerminal(sessionId, RuntimeSessionState.COMPLETED, exitCode = 0)
                    eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
                    finishForegroundRuntime(true, projectSlug, AppStrings.get(context, R.string.rt_bridge_finished_in, "Goose", projectSlug))
                } else {
                    sessionStore.updateRunning(sessionId, toolsExecuted = sawToolCall, mutatingToolsExecuted = sawMutatingTool)
                    val exit = if (userStopRequested) null else outcome.exitCode
                    sessionStore.recordTerminal(sessionId, RuntimeSessionState.FAILED, exitCode = exit, lastError = outcome.failure)
                    eventBus.emit(RuntimeEvent.SessionFailed(sessionId, outcome.failure))
                    if (userStopRequested) cancelForegroundRuntime()
                    else finishForegroundRuntime(false, projectSlug, outcome.failure)
                }
            }
        }.onFailure { error ->
            DebugLog.e("GooseBridge", "Session failed", error)
            val message = friendlyError(error)
            sessionStore.updateRunning(sessionId, toolsExecuted = sawToolCall, mutatingToolsExecuted = sawMutatingTool)
            sessionStore.recordTerminal(sessionId, RuntimeSessionState.FAILED, exitCode = null, lastError = message)
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
            if (userStopRequested) cancelForegroundRuntime()
            else finishForegroundRuntime(false, projectSlug, message)
        }
        activeProcess = null
        activeSessionId = null
        liveSession = null
        RuntimeTaskController.stopAction = null
        PermissionController.clearSessionScoped()
        sessionId
    }

    /**
     * Drives one ACP turn to completion. Returns the terminal outcome; a non-null [failure] means
     * the turn failed (with [exitCode] when the process died), null means it completed cleanly.
     */
    private suspend fun runAcpTurn(
        sessionId: String,
        process: Process,
        nativeProcess: NativeSpawnProcess,
        writer: BufferedWriter,
        guestWorkspacePath: String,
        contextPrompt: String,
    ): AcpOutcome {
        PermissionController.initialize(context)
        val offset = LongArray(1)
        val pending = StringBuilder()

        fun send(frame: String) {
            synchronized(writer) {
                writer.write(frame)
                writer.newLine()
                writer.flush()
            }
        }

        suspend fun nextLine(): String? {
            while (true) {
                val available = nativeProcess.outputFile.length() - offset[0]
                if (available > 0) {
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                        file.seek(offset[0])
                        file.read(bytes)
                    }
                    if (count > 0) {
                        offset[0] += count
                        pending.append(bytes.decodeToString(0, count))
                    }
                }
                val newline = pending.indexOf("\n")
                if (newline >= 0) {
                    val line = pending.substring(0, newline).trimEnd('\r')
                    pending.delete(0, newline + 1)
                    return line
                }
                if (!process.isAlive && nativeProcess.outputFile.length() <= offset[0]) return null
                delay(20)
            }
        }

        suspend fun awaitResponse(expectedId: Long): JSONObject? {
            while (true) {
                val line = nextLine() ?: return null
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (GooseAcp.isResponse(json) && json.optLong("id") == expectedId) return json
                if (json.optString("method") == GooseAcp.NOTIFICATION_SESSION_UPDATE) {
                    consumeUpdate(sessionId, json)
                }
            }
        }

        // Handshake: initialize.
        send(GooseAcp.buildInitialize(1, "PocketDev", "1.0"))
        val initResponse = awaitResponse(1)
            ?: return AcpOutcome("Goose exited before the ACP handshake completed.", processExit(process))
        GooseAcp.errorMessage(initResponse)?.let { return AcpOutcome(it, processExit(process)) }

        // Handshake: create a session (goose assigns its own session id).
        send(GooseAcp.buildSessionNew(2, guestWorkspacePath))
        val newResponse = awaitResponse(2)
            ?: return AcpOutcome("Goose exited before creating an ACP session.", processExit(process))
        GooseAcp.errorMessage(newResponse)?.let { return AcpOutcome(it, processExit(process)) }
        val gooseSessionId = newResponse.optJSONObject("result")?.optString("sessionId")
            ?: return AcpOutcome("Goose did not return an ACP session id.", processExit(process))
        this@GooseRuntimeBridge.gooseSessionId = gooseSessionId

        // Send the user turn.
        send(GooseAcp.buildSessionPrompt(3, gooseSessionId, contextPrompt))

        // Stream loop: consume updates + permission requests until the terminal prompt response.
        var terminal: JSONObject? = null
        while (process.isAlive || nativeProcess.outputFile.length() > offset[0]) {
            val line = nextLine() ?: break
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when {
                GooseAcp.isResponse(json) && json.optLong("id") == 3L -> {
                    terminal = json
                    break
                }
                GooseAcp.isNotification(json) -> consumeUpdate(sessionId, json)
                GooseAcp.isRequest(json) && json.optString("method") == GooseAcp.REQUEST_SESSION_PERMISSION -> {
                    val permission = GooseAcp.permissionRequest(json) ?: continue
                    val allow = decidePermission(sessionId, permission)
                    send(GooseAcp.buildPermissionResponse(json.optLong("id"), allow, permission.options))
                }
            }
        }

        if (userStopRequested && terminal == null) {
            return AcpOutcome("Stopped by user", null)
        }
        if (terminal == null) {
            val exit = processExit(process)
            return AcpOutcome(
                exitCodeFailureMessage(exit) ?: "Goose stopped with exit code $exit",
                exit,
            )
        }
        GooseAcp.errorMessage(terminal)?.let { return AcpOutcome(it, processExit(process)) }
        emitUsageFromResult(sessionId, terminal)
        return AcpOutcome(null, processExit(process))
    }

    private suspend fun emitUsageFromResult(sessionId: String, terminal: JSONObject) {
        val result = GooseAcp.promptResult(terminal) ?: return
        val usage = result.usage ?: return
        emitUsage(sessionId, usage)
    }

    /** Maps one `session/update` notification to RuntimeEvents (text, tools, usage). */
    private suspend fun consumeUpdate(sessionId: String, message: JSONObject) {
        val kind = GooseAcp.sessionUpdateKind(message) ?: return
        val update = message.optJSONObject("params")?.optJSONObject("update") ?: return
        when (kind) {
            GooseAcp.UPDATE_AGENT_MESSAGE_CHUNK -> {
                val text = GooseAcp.chunkText(update)
                if (text.isNotEmpty()) eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, text))
            }
            GooseAcp.UPDATE_TOOL_CALL -> {
                val tool = GooseAcp.toolCall(update) ?: return
                sawToolCall = true
                sawMutatingTool = sawMutatingTool || gooseToolIsMutating(tool.name)
                sessionStore.updateRunning(sessionId, toolsExecuted = sawToolCall, mutatingToolsExecuted = sawMutatingTool)
                eventBus.emit(RuntimeEvent.ToolStarted(sessionId, tool.name, tool.name))
                pushForegroundProgress(AppStrings.get(context, R.string.rt_bridge_running_tool, "Goose", tool.name))
            }
            GooseAcp.UPDATE_USAGE -> {
                GooseAcp.usage(update)?.let { usage -> emitUsage(sessionId, JSONObject()
                    .put("totalTokens", usage.totalTokens ?: JSONObject.NULL)
                    .put("inputTokens", usage.inputTokens ?: JSONObject.NULL)
                    .put("outputTokens", usage.outputTokens ?: JSONObject.NULL)) }
            }
        }
    }

    private suspend fun emitUsage(sessionId: String, usage: JSONObject) {
        val input = usage.optLong("inputTokens", 0L)
        val output = usage.optLong("outputTokens", 0L)
        eventBus.emit(
            RuntimeEvent.SessionUsage(
                sessionId = sessionId,
                agent = "goose",
                providerId = activeProviderId.orEmpty(),
                model = activeModel.orEmpty(),
                usage = TokenUsage(inputTokens = input, outputTokens = output),
                source = UsageSource.CLI,
                projectId = "",
                task = "",
            ),
        )
    }

    /** Resolves a Goose permission request through the app's unified permission system. */
    private suspend fun decidePermission(sessionId: String, permission: GooseAcp.GoosePermissionRequest): Boolean {
        val toolName = permission.toolName.ifBlank { "tool" }
        val command = permission.input.optString("command").ifBlank { null }
        val paths = listOf("file_path", "path", "directory")
            .mapNotNull { key -> permission.input.optString(key).takeIf(String::isNotBlank) }
        val explanation = permission.input.optString("description")
            .ifBlank { command.orEmpty() }
            .ifBlank { "$toolName running in project" }
        val mutating = gooseToolIsMutating(toolName)
        val destructive = DestructiveOpClassifier.isDestructive(null, command)
        val request = PermissionRequest(
            subject = PermissionSubject.TOOL,
            subjectId = toolName,
            operation = command ?: toolName,
            mutating = mutating,
            destructive = destructive,
        )
        return when (val decision = PermissionController.decide(request)) {
            is PermissionDecision.Allowed -> {
                recordToolActivity(toolName)
                eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, explanation))
                true
            }
            is PermissionDecision.Denied -> {
                eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, "Permission denied", "$toolName was denied: ${decision.reason.name}"))
                false
            }
            is PermissionDecision.NeedsApproval -> {
                val toolRequest = ToolRequest(
                    sessionId = sessionId,
                    toolName = toolName,
                    explanation = explanation,
                    affectedPaths = paths,
                    commandPreview = command,
                    risk = DestructiveOpClassifier.riskLevel(mutating = mutating, destructive = destructive),
                )
                val deferred = CompletableDeferred<Boolean>()
                pendingPermissions[toolRequest.approvalId] = PendingPermission(deferred)
                eventBus.emit(RuntimeEvent.ToolRequested(sessionId, toolRequest))
                val allowed = try {
                    deferred.await()
                } finally {
                    pendingPermissions.remove(toolRequest.approvalId)
                }
                if (allowed) recordToolActivity(toolName)
                allowed
            }
        }
    }

    private fun recordToolActivity(toolName: String) {
        sawToolCall = true
        sawMutatingTool = sawMutatingTool || gooseToolIsMutating(toolName)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val pending = pendingPermissions[request.approvalId] ?: return@withContext
        pending.decision.complete(approved)
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            // Preferred: ask goose to cancel cleanly over ACP, then tear down the process.
            val session = gooseSessionId
            if (session != null) {
                runCatching {
                    liveSession?.takeIf { it.sessionId == sessionId && it.process.isAlive }?.let { live ->
                        val frame = GooseAcp.buildCancel(session)
                        synchronized(live.writer) {
                            live.writer.write(frame)
                            live.writer.newLine()
                            live.writer.flush()
                        }
                    }
                }
            }
            val running = activeProcess
            Thread {
                Thread.sleep(500)
                if (running?.isAlive == true) running.destroy()
            }.start()
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val manifest = File(checkpoint, "changes.json")
        if (!backup.isDirectory || !manifest.isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) { checkpoints.checkpointDir(projectId).deleteRecursively() }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    // ------------------------------------------------------------------ helpers

    private fun buildContextPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind): String {
        val sb = StringBuilder()
        val recent = history.recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)
        if (recent.isNotEmpty()) {
            sb.appendLine("Conversation so far:")
            recent.forEach { message ->
                val role = if (message.fromUser) "user" else "assistant"
                sb.appendLine("$role: ${message.text}")
            }
            sb.appendLine()
        }
        sb.appendLine("Working directory: $guestWorkspacePath")
        sb.appendLine("Project kind: ${projectKind.name.lowercase()}")
        sb.appendLine()
        sb.appendLine("Now, respond to this task:")
        sb.append(currentPrompt)
        return sb.toString()
    }

    private fun gooseToolIsMutating(name: String): Boolean {
        val normalized = name.lowercase()
        val readOnlyMarkers = listOf("read", "grep", "glob", "ls", "list", "search", "view", "file_info")
        return readOnlyMarkers.none(normalized::contains)
    }

    private fun processExit(process: Process): Int = runCatching { process.exitValue() }.getOrDefault(-1)

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("not installed", true) -> "Goose is not installed. Open Settings → Coding agent to install it."
            message.contains("user not found", true) || message.contains("authentication", true) ||
                message.contains("api key", true) -> "The provider rejected the saved API key."
            message.contains("Stopped by user") -> "Stopped by user"
            message.isBlank() -> "The Goose runtime could not start."
            else -> message.take(500)
        }
    }

    private fun pushForegroundProgress(@androidx.annotation.StringRes id: Int, vararg args: Any) {
        if (activeSessionId == null) return
        if (android.os.SystemClock.elapsedRealtime() - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        pushForegroundProgress(AppStrings.get(context, id, *args))
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            DebugLog.w("GooseBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private data class PendingPermission(val decision: CompletableDeferred<Boolean>)

    private data class LiveSession(
        val sessionId: String,
        val process: Process,
        val writer: BufferedWriter,
    )

    private data class AcpOutcome(val failure: String?, val exitCode: Int?)

    companion object {
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
        private const val GooseAcpCommandPath = "/root/.local/bin/goose"
    }
}
