package com.mobileharness.code.runtime

import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import org.json.JSONObject

/**
 * Pure Agent Client Protocol (ACP) helpers for Goose, with no Android dependencies.
 *
 * Goose speaks ACP as JSON-RPC 2.0 over newline-delimited JSON on stdin/stdout when launched as
 * `goose acp`. This file owns the wire format (request building + event parsing) so the bridge
 * stays small and the protocol is unit-testable without a device.
 *
 * Flow (verified against Goose's ACP client contract):
 *   initialize → session/new → session/prompt, consuming `session/update` notifications
 *   (`agent_message_chunk`, `tool_call`, `usage_update`) and answering server-initiated
 *   `session/request_permission` requests; the terminal `session/prompt` response carries
 *   `stopReason` + `usage`. `session/cancel` is a fire-and-forget notification.
 */
object GooseAcp {
    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_SESSION_NEW = "session/new"
    const val METHOD_SESSION_PROMPT = "session/prompt"
    const val METHOD_SESSION_CANCEL = "session/cancel"

    const val NOTIFICATION_SESSION_UPDATE = "session/update"
    const val REQUEST_SESSION_PERMISSION = "session/request_permission"

    const val UPDATE_AGENT_MESSAGE_CHUNK = "agent_message_chunk"
    const val UPDATE_TOOL_CALL = "tool_call"
    const val UPDATE_TOOL_CALL_UPDATE = "tool_call_update"
    const val UPDATE_USAGE = "usage_update"

    /** Tool-name keys Goose may place in a permission request's `toolCall` payload. */
    private val TOOL_NAME_KEYS = listOf("toolName", "title", "kind")

    // ------------------------------------------------------------------ requests

    fun buildRequest(id: Long, method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

    fun buildNotification(method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()

    fun buildInitialize(id: Long, clientName: String, clientVersion: String): String = buildRequest(
        id,
        METHOD_INITIALIZE,
        JSONObject()
            .put("protocolVersion", 1)
            .put("clientInfo", JSONObject().put("name", clientName).put("version", clientVersion))
            // Do not advertise client-side filesystem/terminal delegation: Goose runs its own
            // developer tools against the bind-mounted workspace, and every mutating tool call is
            // surfaced to the app's unified approval system through session/request_permission.
            .put(
                "clientCapabilities",
                JSONObject()
                    .put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false))
                    .put("terminal", false),
            ),
    )

    fun buildSessionNew(id: Long, cwd: String): String = buildRequest(
        id,
        METHOD_SESSION_NEW,
        JSONObject().put("cwd", cwd).put("mcpServers", org.json.JSONArray()),
    )

    fun buildSessionPrompt(id: Long, sessionId: String, text: String): String = buildRequest(
        id,
        METHOD_SESSION_PROMPT,
        JSONObject()
            .put("sessionId", sessionId)
            .put(
                "prompt",
                org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)),
            ),
    )

    fun buildCancel(sessionId: String): String = buildNotification(
        METHOD_SESSION_CANCEL,
        JSONObject().put("sessionId", sessionId),
    )

    /** Client answer to a server-initiated `session/request_permission`. */
    fun buildPermissionResponse(id: Long, allow: Boolean, options: List<GoosePermissionOption>): String {
        val optionId = selectOption(options, allow)
        val result = if (optionId == null) {
            JSONObject().put("outcome", "cancelled")
        } else {
            JSONObject().put("outcome", "selected").put("optionId", optionId)
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
    }

    // ------------------------------------------------------------------ parsing

    /** True when the line is a JSON-RPC response (has an id and no method). */
    fun isResponse(message: JSONObject): Boolean =
        message.has("id") && !message.has("method")

    /** True when the line is a server-initiated request (has both an id and a method). */
    fun isRequest(message: JSONObject): Boolean =
        message.has("id") && message.has("method")

    fun isNotification(message: JSONObject): Boolean =
        !message.has("id") && message.has("method")

    /** Returns the `session/update` notification's `sessionUpdate` kind, or null. */
    fun sessionUpdateKind(message: JSONObject): String? {
        if (message.optString("method") != NOTIFICATION_SESSION_UPDATE) return null
        val update = message.optJSONObject("params")?.optJSONObject("update") ?: return null
        return update.optString("sessionUpdate").ifBlank { null }
    }

    /** Streaming assistant text from an `agent_message_chunk` update. */
    fun chunkText(update: JSONObject): String {
        val content = update.optJSONObject("content") ?: return ""
        return content.optString("text")
    }

    data class GooseToolCall(val name: String, val input: JSONObject)

    /** Tool name + raw input from a `tool_call` update. */
    fun toolCall(update: JSONObject): GooseToolCall? {
        val meta = update.optJSONObject("_meta")?.optJSONObject("goose")?.optJSONObject("toolCall")
        val name = TOOL_NAME_KEYS.mapNotNull { key ->
            meta?.optString(key).takeUnless { it.isNullOrBlank() }
                ?: update.optString(key).takeUnless { it.isBlank() }
        }.firstOrNull() ?: return null
        val input = update.optJSONObject("rawInput")
            ?: update.optJSONObject("input")
            ?: JSONObject()
        return GooseToolCall(name, input)
    }

    data class GooseUsage(
        val totalTokens: Long?,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val contextWindow: Long?,
    )

    fun usage(update: JSONObject): GooseUsage? {
        val hasAny = listOf("totalTokens", "inputTokens", "outputTokens", "used", "size").any(update::has)
        if (!hasAny) return null
        fun long(name: String): Long? = if (update.has(name) && !update.isNull(name)) update.optLong(name) else null
        return GooseUsage(
            totalTokens = long("totalTokens"),
            inputTokens = long("inputTokens"),
            outputTokens = long("outputTokens"),
            contextWindow = long("size").takeIf { it != null && it > 0 },
        )
    }

    data class GoosePermissionRequest(val toolName: String, val input: JSONObject, val options: List<GoosePermissionOption>)

    /** Server-initiated `session/request_permission` payload (tool name, input and options). */
    fun permissionRequest(message: JSONObject): GoosePermissionRequest? {
        if (message.optString("method") != REQUEST_SESSION_PERMISSION) return null
        val params = message.optJSONObject("params") ?: return null
        val toolCall = params.optJSONObject("toolCall") ?: return null
        val tool = toolCall(toolCall) ?: GooseToolCall("tool", JSONObject())
        val optionsArray = params.optJSONArray("options")
        val options = buildList {
            if (optionsArray == null) return@buildList
            for (index in 0 until optionsArray.length()) {
                optionsArray.optJSONObject(index)?.let { add(it) }
            }
        }.map { GoosePermissionOption(it.optString("kind"), it.optString("optionId")) }
        return GoosePermissionRequest(tool.name, tool.input, options)
    }

    data class GoosePromptResult(val stopReason: String?, val usage: JSONObject?)

    /** Terminal `session/prompt` response's `result` (stopReason + usage). */
    fun promptResult(message: JSONObject): GoosePromptResult? {
        val result = message.optJSONObject("result") ?: return null
        val usage = result.optJSONObject("usage")
        return GoosePromptResult(
            stopReason = result.optString("stopReason").ifBlank { null },
            usage = usage,
        )
    }

    /** Non-blank JSON-RPC error message, or null. */
    fun errorMessage(message: JSONObject): String? =
        message.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ approval

    data class GoosePermissionOption(val kind: String, val optionId: String)

    /**
     * Picks the safest option id for an allow/deny decision. On allow it prefers `allow_once`
     * (never a blanket "always allow"); on deny it prefers `reject_once`. Returns null when no
     * matching option exists (the bridge then answers `outcome: cancelled`).
     */
    fun selectOption(options: List<GoosePermissionOption>, allow: Boolean): String? {
        if (allow) {
            return options.firstOrNull { it.kind == "allow_once" }?.optionId
                ?: options.firstOrNull { "allow" in it.kind }?.optionId
        }
        return options.firstOrNull { it.kind == "reject_once" }?.optionId
            ?: options.firstOrNull { "reject" in it.kind }?.optionId
    }
}

/**
 * Provider environment for a Goose session. Goose reads its model/provider from `GOOSE_PROVIDER`
 * and `GOOSE_MODEL`, and the credential from `GOOSE_PROVIDER__API_KEY` (never a plaintext config
 * file). `GOOSE_PROVIDER__HOST` points custom endpoints at their base URL.
 *
 * Provider-name mapping is documented, not invented: names are the standard lowercase identifiers
 * Goose resolves against its provider registry. On-device verification of each provider is a
 * release checklist item — see the Goose runtime integration notes.
 */
fun gooseProviderEnvironment(profile: ProviderProfile, secret: String): Map<String, String> {
    val providerName = when (profile.kind) {
        ProviderKind.ANTHROPIC -> "anthropic"
        ProviderKind.LLM_ROUTER -> "openrouter"
        ProviderKind.DEEPSEEK -> "deepseek"
        ProviderKind.KIMI -> "moonshot"
        ProviderKind.NVIDIA_NIM -> "nvidia"
        ProviderKind.CUSTOM -> "openai"
        else -> "anthropic"
    }
    val environment = linkedMapOf(
        "GOOSE_PROVIDER" to providerName,
        "GOOSE_MODEL" to profile.model,
        "GOOSE_PROVIDER__API_KEY" to secret,
        // Fail-closed approvals: route every tool call through the app's unified permission system.
        "GOOSE_MODE" to "approve",
        // Bound the autonomous loop the same way the other CLIs do (Claude Code uses --max-turns 25).
        "GOOSE_MAX_TURNS" to "25",
        "GOOSE_TELEMETRY_ENABLED" to "false",
        "GOOSE_DISABLE_KEYRING" to "1",
        "GOOSE_DISABLE_SESSION_NAMING" to "true",
    )
    // Custom endpoints need their base URL; fixed-base providers resolve their own host.
    val host = when (profile.kind) {
        ProviderKind.CUSTOM -> profile.resolvedBaseUrl.trim().trimEnd('/').ifBlank { null }
        ProviderKind.NVIDIA_NIM -> profile.kind.defaultBaseUrl.trim().trimEnd('/')
        else -> null
    }
    if (!host.isNullOrBlank()) environment["GOOSE_PROVIDER__HOST"] = host
    return environment
}
