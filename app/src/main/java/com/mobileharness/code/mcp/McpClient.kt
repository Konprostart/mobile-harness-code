package com.mobileharness.code.mcp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure framing helpers for the MCP stdio transport. MCP over stdio is newline-delimited JSON-RPC:
 * one JSON object per line, with `\n`-escaped newlines inside strings (so line-splitting is safe).
 *
 * The client never invokes a shell: [splitCommand] is a plain whitespace split passed to
 * `ProcessBuilder`, so shell operators cannot execute arbitrary code.
 */
object McpStdio {
    /** Appends the transport delimiter to a JSON-RPC message. */
    fun encode(message: String): String = message + "\n"

    /** Splits accumulated bytes into complete lines, returning (completeLines, remainder). */
    fun decodeLines(accumulated: String): Pair<List<String>, String> {
        val lines = accumulated.split('\n')
        if (lines.size <= 1) return emptyList<String>() to accumulated
        val complete = lines.dropLast(1)
        return complete to lines.last()
    }

    /** Splits a command line into argv without a shell (no quoting, pipes or redirection). */
    fun splitCommand(command: String): List<String> =
        command.trim().split(Regex("\\s+")).filter(String::isNotBlank)
}

/**
 * Minimal, fail-closed MCP client implementing the JSON-RPC 2.0 lifecycle
 * (`initialize` → `notifications/initialized` → `tools/list` → `tools/call`) over two transports:
 *
 *  - HTTP / Streamable HTTP: a POST to [McpServerConfig.url] with an optional per-server Bearer
 *    token. SSE bodies are tolerated by extracting the first `data:` JSON line.
 *  - Stdio: a fresh local process per operation, spoken to over newline-delimited JSON-RPC.
 *
 * The client never reads the app API-key vault; it only ever sends the server's own token.
 */
class McpClient(private val clientVersion: String = "1.3.1") {
    private var nextId = 1L
    private val idLock = Any()

    private fun allocateId(): Long = synchronized(idLock) { nextId++ }

    suspend fun initializeAndListTools(
        config: McpServerConfig,
        ecoEnabled: Boolean = false,
    ): McpListResult = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext McpListResult.Failure("Server is disabled.")
        if (!config.isComplete()) return@withContext McpListResult.Failure("Server configuration is incomplete.")
        val urlValidation = when (config.transport) {
            McpTransport.HTTP -> validateUrl(config.url)
            McpTransport.STDIO -> null
        }
        if (urlValidation != null) return@withContext McpListResult.Failure(urlValidation)

        when (config.transport) {
            McpTransport.HTTP -> httpList(config, ecoEnabled)
            McpTransport.STDIO -> stdioList(config, ecoEnabled)
        }
    }

    suspend fun callTool(
        config: McpServerConfig,
        tool: McpTool,
        arguments: org.json.JSONObject,
        ecoEnabled: Boolean = false,
    ): McpCallResult = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext McpCallResult.Failure("Server is disabled.", McpCallFailureKind.OTHER)
        if (!config.isComplete()) return@withContext McpCallResult.Failure("Server configuration is incomplete.", McpCallFailureKind.OTHER)
        val urlValidation = when (config.transport) {
            McpTransport.HTTP -> validateUrl(config.url)
            McpTransport.STDIO -> null
        }
        if (urlValidation != null) return@withContext McpCallResult.Failure(urlValidation, McpCallFailureKind.NETWORK)

        when (config.transport) {
            McpTransport.HTTP -> httpCall(config, tool, arguments, ecoEnabled)
            McpTransport.STDIO -> stdioCall(config, tool, arguments, ecoEnabled)
        }
    }

    private fun validateUrl(url: String): String? {
        if (!McpPolicy.isAllowedHttpUrl(url)) {
            return "Unsafe MCP URL. Use https:// or a local/private address."
        }
        return null
    }

    // --- HTTP transport ----------------------------------------------------------------------

    private fun httpList(config: McpServerConfig, ecoEnabled: Boolean): McpListResult {
        val initId = allocateId()
        when (val initResponse = httpPost(config, McpJsonRpc.initializeRequest(initId, clientVersion), ecoEnabled)) {
            is HttpResult.Failure -> return McpListResult.Failure(initResponse.message)
            is HttpResult.Ok -> {
                McpJsonRpc.extractError(initResponse.body)?.let { return McpListResult.Failure(it) }
            }
        }

        // Fire-and-forget notification; a server that closes here is still usable for tools/list.
        httpPost(config, McpJsonRpc.initializedNotification(), ecoEnabled)

        val listId = allocateId()
        val listResponse = when (val r = httpPost(config, McpJsonRpc.toolsListRequest(listId), ecoEnabled)) {
            is HttpResult.Failure -> return McpListResult.Failure(r.message)
            is HttpResult.Ok -> r.body
        }
        McpJsonRpc.extractError(listResponse)?.let { return McpListResult.Failure(it) }
        return McpListResult.Success(McpJsonRpc.parseToolsList(listResponse))
    }

    private fun httpCall(
        config: McpServerConfig,
        tool: McpTool,
        arguments: org.json.JSONObject,
        ecoEnabled: Boolean,
    ): McpCallResult {
        val callId = allocateId()
        val response = when (val r = httpPost(config, McpJsonRpc.toolsCallRequest(callId, tool.name, arguments), ecoEnabled)) {
            is HttpResult.Failure -> return McpCallResult.Failure(r.message, r.kind)
            is HttpResult.Ok -> r.body
        }
        McpJsonRpc.extractError(response)?.let { return McpCallResult.Failure(it, McpCallFailureKind.PROTOCOL) }
        val output = McpJsonRpc.parseToolsCall(response)
        return McpCallResult.Success(
            McpPolicy.truncate(output.text, McpPolicy.maxOutputChars(ecoEnabled)),
            output.isError,
        )
    }

    /** Result of one HTTP POST, distinguishing auth/timeout/network failures for better messages. */
    private sealed interface HttpResult {
        data class Ok(val body: String) : HttpResult
        data class Failure(val kind: McpCallFailureKind, val message: String) : HttpResult
    }

    private fun httpPost(config: McpServerConfig, body: String, ecoEnabled: Boolean): HttpResult {
        return try {
            val connection = (java.net.URL(config.url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = McpPolicy.timeoutMillis(ecoEnabled).toInt()
                readTimeout = McpPolicy.timeoutMillis(ecoEnabled).toInt()
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("Content-Type", "application/json")
                if (config.bearerToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.bearerToken}")
                }
                doOutput = true
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = readBounded(stream)
                if (code !in 200..299) {
                    HttpResult.Failure(McpPolicy.classifyHttpStatus(code), "MCP server returned HTTP $code.")
                } else {
                    HttpResult.Ok(extractSseJson(raw) ?: raw)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            HttpResult.Failure(McpCallFailureKind.TIMEOUT, "MCP server timed out.")
        } catch (e: Exception) {
            HttpResult.Failure(McpCallFailureKind.NETWORK, e.message ?: "MCP request failed.")
        }
    }

    /** Reads an HTTP response body up to [MAX_HTTP_RESPONSE_CHARS] characters. */
    private fun readBounded(stream: java.io.InputStream?): String =
        stream?.bufferedReader()?.use { reader ->
            buildString {
                val buffer = CharArray(8 * 1024)
                var total = 0
                while (total < MAX_HTTP_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_HTTP_RESPONSE_CHARS - total))
                    if (count <= 0) break
                    append(buffer, 0, count)
                    total += count
                }
            }
        }.orEmpty()

    /** Returns the first `data:` payload that is a JSON object, otherwise null. */
    private fun extractSseJson(body: String): String? {
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") return null
            if (payload.startsWith("{")) return payload
        }
        return null
    }

    // --- Stdio transport ---------------------------------------------------------------------

    private fun stdioList(config: McpServerConfig, ecoEnabled: Boolean): McpListResult {
        val initId = allocateId()
        val initResponse = stdioRequest(config, McpJsonRpc.initializeRequest(initId, clientVersion), ecoEnabled)
            ?: return McpListResult.Failure("Stdio MCP server did not respond to initialize.")
        McpJsonRpc.extractError(initResponse)?.let { return McpListResult.Failure(it) }
        val listId = allocateId()
        val listResponse = stdioRequest(config, McpJsonRpc.toolsListRequest(listId), ecoEnabled)
            ?: return McpListResult.Failure("Stdio MCP server did not respond to tools/list.")
        McpJsonRpc.extractError(listResponse)?.let { return McpListResult.Failure(it) }
        return McpListResult.Success(McpJsonRpc.parseToolsList(listResponse))
    }

    private fun stdioCall(
        config: McpServerConfig,
        tool: McpTool,
        arguments: org.json.JSONObject,
        ecoEnabled: Boolean,
    ): McpCallResult {
        val callId = allocateId()
        val response = stdioRequest(config, McpJsonRpc.toolsCallRequest(callId, tool.name, arguments), ecoEnabled)
            ?: return McpCallResult.Failure("Stdio MCP server did not respond to tools/call.", McpCallFailureKind.NETWORK)
        McpJsonRpc.extractError(response)?.let { return McpCallResult.Failure(it, McpCallFailureKind.PROTOCOL) }
        val output = McpJsonRpc.parseToolsCall(response)
        return McpCallResult.Success(
            McpPolicy.truncate(output.text, McpPolicy.maxOutputChars(ecoEnabled)),
            output.isError,
        )
    }

    /**
     * Launches the configured stdio command, sends one JSON-RPC request and reads back the first
     * JSON line carrying an id. A fresh process is used per operation so a crashing server cannot
     * leave a zombie behind; any failure is reported (fail-closed).
     */
    private fun stdioRequest(config: McpServerConfig, request: String, ecoEnabled: Boolean): String? {
        val argv = McpStdio.splitCommand(config.command)
        if (argv.isEmpty()) return null
        return runCatching {
            val process = ProcessBuilder(argv)
                .redirectErrorStream(false)
                .start()
            try {
                process.outputStream.use { it.write(McpStdio.encode(request).toByteArray(StandardCharsets.UTF_8)) }
                process.outputStream.close()
                drainStderr(process)
                val timeoutMs = McpPolicy.timeoutMillis(ecoEnabled)
                readJsonLine(process, timeoutMs)
            } finally {
                process.destroy()
            }
        }.getOrNull()
    }

    private fun drainStderr(process: Process) {
        val thread = Thread {
            runCatching {
                val reader = BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
                var skipped = 0
                val buffer = CharArray(4096)
                while (skipped < MAX_STDIO_STDERR_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_STDIO_STDERR_CHARS - skipped))
                    if (count <= 0) break
                    skipped += count
                }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    private fun readJsonLine(process: Process, timeoutMs: Long): String? {
        val result = AtomicReference<String?>(null)
        val thread = Thread {
            runCatching {
                val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
                var line = reader.readLine()
                var lines = 0
                while (line != null && lines < MAX_STDIO_LINES) {
                    lines++
                    val trimmed = line.trim()
                    if (trimmed.startsWith("{")) {
                        val obj = runCatching { org.json.JSONObject(trimmed) }.getOrNull()
                        if (obj != null && obj.has("id")) {
                            result.set(trimmed)
                            break
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) {
            process.destroyForcibly()
            return null
        }
        return result.get()
    }

    private companion object {
        const val MAX_HTTP_RESPONSE_CHARS = 256 * 1024
        const val MAX_STDIO_STDERR_CHARS = 64 * 1024
        const val MAX_STDIO_LINES = 256
    }
}
