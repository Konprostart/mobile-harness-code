package com.mobileharness.code.terminal

/**
 * An interactive local PTY terminal session. It owns the [PtyTransport] from the moment it is
 * created, decodes the PTY byte stream through the [Vt100Screen] emulator on a dedicated (daemon)
 * reader thread, and exposes bounded send/resize/interrupt/close operations. The reader loop is
 * fully isolated from the UI thread, so a runaway process cannot block the app.
 */
class LocalTerminalSession(
    private val transport: PtyTransport,
    cols: Int,
    rows: Int,
    ecoEnabled: () -> Boolean,
) {
    enum class State { CONNECTED, CLOSED }

    val screen = Vt100Screen(cols, rows, if (ecoEnabled()) MAX_SCROLLBACK_ECO else MAX_SCROLLBACK)

    @Volatile
    private var currentState = State.CONNECTED

    /** Invoked from the reader thread after each parsed output chunk (UI recomposition hint). */
    @Volatile
    var onOutput: (() -> Unit)? = null

    /** Invoked once, from any thread, when the session ends (with an optional failure reason). */
    @Volatile
    var onClosed: ((String?) -> Unit)? = null

    private val readerThread = Thread(::readLoop, "local-pty-reader").apply { isDaemon = true }

    init {
        readerThread.start()
    }

    val state: State get() = currentState

    /** Whether the PTY is still genuinely usable (session connected and the master fd/process live). */
    fun isAlive(): Boolean = currentState == State.CONNECTED && transport.isAlive

    /** Sends arbitrary UTF-8 bytes to the PTY (e.g. a typed command line + Enter). */
    fun sendInput(text: String) {
        if (currentState != State.CONNECTED) return
        runCatching { transport.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Sends a semantic terminal key press (arrow, function, Enter, …). */
    fun sendKey(key: TerminalKey, ctrl: Boolean = false, alt: Boolean = false) {
        sendInput(TerminalKeyMapper.sequence(key, ctrl, alt))
    }

    /** Sends a printable character (Ctrl+letter folds to its C0 control code; Alt prefixes ESC). */
    fun sendChar(ch: Char, ctrl: Boolean = false, alt: Boolean = false) {
        sendInput(TerminalKeyMapper.charSequence(ch, ctrl, alt))
    }

    /** Sends Ctrl+C (SIGINT via the PTY line discipline, the same as a real terminal). */
    fun interrupt() {
        sendChar('c', ctrl = true)
    }

    /** Resizes both the local emulator grid and the underlying PTY (TIOCSWINSZ). */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols < 1 || newRows < 1) return
        screen.resize(newCols, newRows)
        if (currentState == State.CONNECTED) {
            runCatching { transport.resize(newCols, newRows) }
        }
    }

    /** Idempotent close: stops the reader, closes the transport, releases the process. */
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
            // Expected on close: the PTY stream is torn down while a read is pending.
        } finally {
            markClosed(null)
        }
    }

    private companion object {
        const val MAX_SCROLLBACK = 2000
        const val MAX_SCROLLBACK_ECO = 500
    }
}
