package com.mobileharness.code.runtime

import com.mobileharness.code.terminal.PtyTransport
import java.io.File
import java.io.InputStream

/**
 * Adapter that exposes the native `pocketspawn` PTY (see `pocket_spawn.c`: `posix_openpt`/`fork`/
 * `setsid`/`TIOCSCTTY`/`TIOCSWINSZ`) as a generic [PtyTransport] for the local interactive
 * terminal. The transport keeps the raw process handle and master-fd resize out of the pure
 * terminal session.
 */
private class NativePtyTransport(private val process: NativeSpawnProcess) : PtyTransport {
    override val isOpen: Boolean get() = process.isAlive
    override val isAlive: Boolean get() = process.isAlive
    override fun inputStream(): InputStream = process.inputStream

    override fun write(bytes: ByteArray) {
        runCatching {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
    }

    override fun resize(cols: Int, rows: Int) {
        process.resize(rows, cols)
    }

    override fun close() {
        runCatching { process.destroy() }
    }
}

/**
 * Factory for launching a local interactive PTY process. [argv] is the command to run inside the
 * runtime (typically the PRoot-wrapped shell, e.g. `proot -R <rootfs> /bin/bash`); the caller is
 * responsible for constructing that argv through the existing [RuntimeInstaller]/PRoot layer.
 * This never adds a new runtime or downloads anything — it only wires the native PTY spawn.
 */
object LocalPtyFactory {
    fun start(
        argv: List<String>,
        environment: Map<String, String>,
        cwd: String,
        outputFile: File,
        cols: Int,
        rows: Int,
    ): PtyTransport {
        val process = NativeSpawnProcess.start(
            argv = argv,
            environment = environment,
            cwd = cwd,
            outputFile = outputFile,
            pseudoTerminal = true,
            ptyRows = rows,
            ptyColumns = cols,
        )
        return NativePtyTransport(process)
    }
}
