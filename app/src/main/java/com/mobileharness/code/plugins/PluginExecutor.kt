package com.mobileharness.code.plugins

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Plugin tool execution, ported and adapted from AndroidHarness 4.0
 * (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * Execution is bounded: every tool call carries a timeout, output is capped, and a per-run call
 * budget prevents unbounded loops. Tools that run scripts (`.py`, `.js`, `.rb`, …) are executed via
 * their interpreter; a bare command is executed directly without a shell (so shell operators cannot
 * run arbitrary code). The permission decision is made by [requirePermission] *before* any process
 * starts and is enforced by the caller (the guest runtime / Sidekick) via the existing privilege
 * policy.
 */
object PluginExecutor {

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    /** The permission a tool needs to run. */
    fun requirePermission(tool: PluginTool): PluginPermission? {
        if (tool.command.isBlank()) return null
        return PluginPermission.EXEC
    }

    /** Resolves a tool into its argv (interpreter + script + arguments), without a shell. */
    fun buildCommand(tool: PluginTool, argumentsJson: String): List<String> {
        val command = tool.command.trim()
        if (command.isEmpty()) return emptyList()
        val words = splitCommand(command)
        if (words.isEmpty()) return emptyList()
        val interpreter = PluginRuntimeDetection.interpreterFor(command)
        val base = if (interpreter == null) words else listOf(interpreter) + listOf(command)
        val withArgs = if (argumentsJson.isBlank()) base else base + argumentsJson
        return withArgs
    }

    /** Splits a command line into argv without a shell (no quoting, pipes or redirection). */
    fun splitCommand(command: String): List<String> =
        command.trim().split(Regex("\\s+")).filter(String::isNotBlank)

    /** Runs [argv] in [workDir], capturing bounded output with a timeout. */
    fun run(
        argv: List<String>,
        workDir: File,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
    ): RunResult {
        if (argv.isEmpty()) return RunResult(-1, "", "Empty command.", false)
        val process = try {
            val builder = ProcessBuilder(argv)
            builder.directory(workDir)
            builder.redirectErrorStream(false)
            builder.environment().putAll(env)
            builder.start()
        } catch (e: Exception) {
            return RunResult(-1, "", e.message ?: "Failed to start.", false)
        }
        return try {
            val stdout = readBounded(process.inputStream, MAX_OUTPUT)
            val stderr = readBounded(process.errorStream, MAX_OUTPUT)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RunResult(-1, stdout, stderr, timedOut = true)
            }
            RunResult(process.exitValue(), stdout, stderr, timedOut = false)
        } catch (e: Exception) {
            RunResult(-1, "", e.message ?: "Execution failed.", false)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** A per-run call budget that prevents unbounded tool loops. */
    class CallBudget(private val limit: Int) {
        var remaining = limit
            private set

        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }

        val exhausted: Boolean get() = remaining <= 0
    }

    private fun readBounded(stream: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit - total))
            if (count < 0) break
            out.write(buffer, 0, count)
            total += count
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private const val MAX_OUTPUT = 256 * 1024
}
