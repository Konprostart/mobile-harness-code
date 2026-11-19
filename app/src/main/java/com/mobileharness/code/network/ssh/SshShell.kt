package com.mobileharness.code.network.ssh

import com.mobileharness.code.terminal.Vt100Screen
import java.io.InputStream
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session

/** A single key or control sequence that the interactive shell understands. */
enum class SshControlKey {
    ENTER, TAB, ESC, CTRL_C, CTRL_D, CTRL_Z, BACKSPACE,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
}

/**
 * Pure encoding of terminal control keys to the byte sequences a PTY expects. Kept free of any
 * sshj/Android dependency so it is trivially unit-testable; [ENTER] and [TAB] map to the raw
 * control bytes the remote shell's canonical mode interprets (which is what a real terminal
 * sends), while arrows use CSI and Ctrl keys use the classic C0 control codes.
 */
object SshShell {
    fun controlBytes(key: SshControlKey): String = when (key) {
        SshControlKey.ENTER -> "\r"
        SshControlKey.TAB -> "\t"
        SshControlKey.ESC -> "\u001b"
        SshControlKey.CTRL_C -> "\u0003"
        SshControlKey.CTRL_D -> "\u0004"
        SshControlKey.CTRL_Z -> "\u001a"
        SshControlKey.BACKSPACE -> "\u007f"
        SshControlKey.ARROW_UP -> "\u001b[A"
        SshControlKey.ARROW_DOWN -> "\u001b[B"
        SshControlKey.ARROW_LEFT -> "\u001b[D"
        SshControlKey.ARROW_RIGHT -> "\u001b[C"
    }

    const val MAX_SCROLLBACK_LINES = 2000
    const val MAX_SCROLLBACK_LINES_ECO = 500
}

/**
 * Thin abstraction over the sshj shell channel so [SshShellSession]'s lifecycle can be exercised
 * in JVM unit tests with a fake, without spinning up a live SSH server.
 */
interface SshPtyTransport {
    val isOpen: Boolean
    fun inputStream(): InputStream
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}

/** Outcome of starting an interactive shell channel. */
sealed interface SshShellStartResult {
    data class Success(val session: SshShellSession) : SshShellStartResult
    data class Failure(val message: String) : SshShellStartResult
}

/**
 * An interactive PTY shell session: it owns the sshj transport from the moment it is created,
 * decodes the remote byte stream through the [Vt100Screen] emulator on a dedicated (daemon)
 * reader thread, and exposes bounded send/resize/close operations. The reader loop is fully
 * isolated from the UI thread and never lets a remote flood block the app.
 */
class SshShellSession(
    private val transport: SshPtyTransport,
    cols: Int,
    rows: Int,
    ecoEnabled: () -> Boolean,
) {
    enum class State { CONNECTED, CLOSED }

    val screen = Vt100Screen(cols, rows, if (ecoEnabled()) SshShell.MAX_SCROLLBACK_LINES_ECO else SshShell.MAX_SCROLLBACK_LINES)

    @Volatile
    private var currentState = State.CONNECTED

    /** Invoked from the reader thread after each parsed output chunk (UI recomposition hint). */
    @Volatile
    var onOutput: (() -> Unit)? = null

    /** Invoked once, from any thread, when the session ends (with an optional failure reason). */
    @Volatile
    var onClosed: ((String?) -> Unit)? = null

    private val readerThread = Thread(::readLoop, "ssh-pty-reader").apply { isDaemon = true }

    init {
        readerThread.start()
    }

    val state: State get() = currentState

    /** Sends arbitrary UTF-8 bytes to the remote shell (e.g. a typed command line + Enter). */
    fun sendInput(text: String) {
        if (currentState != State.CONNECTED) return
        runCatching { transport.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Sends a single terminal control key/sequence. */
    fun sendControl(key: SshControlKey) {
        sendInput(SshShell.controlBytes(key))
    }

    /** Resizes both the local emulator grid and the remote PTY (window-change message). */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols < 1 || newRows < 1) return
        screen.resize(newCols, newRows)
        if (currentState == State.CONNECTED) {
            runCatching { transport.resize(newCols, newRows) }
        }
    }

    /** Idempotent close: stops the reader, closes the channel/session/transport. */
    fun close() {
        markClosed(null)
    }

    @Synchronized
    private fun markClosed(reason: String?) {
        if (currentState == State.CLOSED) return
        currentState = State.CLOSED
        runCatching { transport.close() }
        onClosed?.invoke(reason)
    }

    private fun readLoop() {
        try {
            val reader = transport.inputStream().bufferedReader(Charsets.UTF_8)
            val buffer = CharArray(4096)
            while (currentState == State.CONNECTED && transport.isOpen) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    screen.write(String(buffer, 0, count))
                    onOutput?.invoke()
                }
            }
        } catch (ignored: Throwable) {
            // Expected on close: the channel stream is torn down while a read is pending.
        } finally {
            markClosed(null)
        }
    }
}

/** [SshPtyTransport] backed by the real sshj [Session.Shell] channel. */
class SshjPtyTransport(
    private val ssh: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
) : SshPtyTransport {
    override val isOpen: Boolean get() = shell.isOpen

    override fun inputStream(): InputStream = shell.inputStream

    override fun write(bytes: ByteArray) {
        shell.outputStream.write(bytes)
        shell.outputStream.flush()
    }

    override fun resize(cols: Int, rows: Int) {
        shell.changeWindowDimensions(cols, rows, 0, 0)
    }

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
        runCatching { ssh.disconnect() }
    }
}
