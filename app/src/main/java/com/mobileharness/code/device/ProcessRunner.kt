package com.mobileharness.code.device

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs a [Process] with a hard timeout and a bounded output budget, mirroring the SSH `exec`
 * and MCP tool policies. Output is collected concurrently from stdout/stderr so a chatty command
 * can never deadlock, and both streams are capped at [maxOutputBytes] so a runaway command cannot
 * exhaust memory.
 */
internal object ProcessRunner {
    fun run(process: Process, timeoutMillis: Long, maxOutputBytes: Int): PrivilegeResult {
        val stdoutCollector = StreamCollector(process.inputStream, maxOutputBytes)
        val stderrCollector = StreamCollector(process.errorStream, maxOutputBytes)
        val stdoutThread = Thread(stdoutCollector, "priv-stdout").apply { isDaemon = true; start() }
        val stderrThread = Thread(stderrCollector, "priv-stderr").apply { isDaemon = true; start() }

        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            stdoutThread.join(500)
            stderrThread.join(500)
            return PrivilegeResult(
                outcome = PrivilegeOutcome.TIMEOUT,
                stdout = stdoutCollector.result(),
                stderr = stderrCollector.result(),
                error = "command timed out",
            )
        }

        stdoutThread.join(2_000)
        stderrThread.join(2_000)
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        val stdout = stdoutCollector.result()
        val stderr = stderrCollector.result()
        return if (exitCode == 0) {
            PrivilegeResult(outcome = PrivilegeOutcome.SUCCESS, exitCode = 0, stdout = stdout, stderr = stderr)
        } else {
            PrivilegeResult(
                outcome = PrivilegeOutcome.ERROR,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                error = "exit code $exitCode",
            )
        }
    }

    private class StreamCollector(private val stream: InputStream, private val maxBytes: Int) : Runnable {
        private val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, 4096))
        private var truncated = false

        override fun run() {
            runCatching {
                val chunk = ByteArray(4096)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    val room = maxBytes - buffer.size()
                    if (room <= 0) {
                        truncated = true
                        continue
                    }
                    buffer.write(chunk, 0, minOf(n, room))
                    if (n > room) truncated = true
                }
            }
            runCatching { stream.close() }
        }

        fun result(): String {
            val text = String(buffer.toByteArray(), Charsets.UTF_8)
            return if (truncated) text + "…" else text
        }
    }
}
