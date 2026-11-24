package com.mobileharness.code.terminal

import java.io.InputStream

/**
 * A generic PTY transport for an interactive local terminal session. This is the local analogue of
 * the SSH `SshPtyTransport`: the session owns the transport, decodes the byte stream through the
 * [Vt100Screen] emulator on a dedicated reader thread, and exposes bounded send/resize/close ops.
 *
 * A concrete implementation wraps the native `pocketspawn` PTY (`posix_openpt`/`fork`/`setsid`/
 * `TIOCSCTTY`/`TIOCSWINSZ`); a fake implementation drives the JVM unit tests.
 */
interface PtyTransport {
    val isOpen: Boolean

    /** Whether the PTY is still genuinely usable (live master fd + live process). */
    val isAlive: Boolean get() = isOpen

    fun inputStream(): InputStream
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}

/** Outcome of starting a local interactive terminal session. */
sealed interface LocalTerminalStartResult {
    data class Success(val session: LocalTerminalSession) : LocalTerminalStartResult
    data class Failure(val message: String) : LocalTerminalStartResult
}
