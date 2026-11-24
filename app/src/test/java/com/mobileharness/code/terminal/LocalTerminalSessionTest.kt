package com.mobileharness.code.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class FakeLocalPtyTransport(
    var closed: Boolean = false,
) : PtyTransport {
    private val pipeIn = java.io.PipedInputStream()
    private val pipeOut = java.io.PipedOutputStream(pipeIn)
    var writes = StringBuilder()
    var lastResize: Pair<Int, Int>? = null
    override val isOpen: Boolean get() = !closed
    override val isAlive: Boolean get() = !closed

    /** Blocks until [feed] or [close], so the session stays CONNECTED during a test. */
    override fun inputStream(): InputStream = pipeIn

    /** Feeds PTY output bytes to the reader thread. */
    fun feed(text: String) {
        pipeOut.write(text.toByteArray(Charsets.UTF_8))
        pipeOut.flush()
    }

    override fun write(bytes: ByteArray) { writes.append(String(bytes, Charsets.UTF_8)) }
    override fun resize(cols: Int, rows: Int) { lastResize = cols to rows }
    override fun close() { closed = true; runCatching { pipeOut.close() } }
}

class LocalTerminalSessionTest {

    @Test fun writesInputToTransport() {
        val transport = FakeLocalPtyTransport()
        val session = LocalTerminalSession(transport, 80, 24) { false }
        session.sendInput("echo hello\r")
        Thread.sleep(30)
        assertEquals("echo hello\r", transport.writes.toString())
        session.close()
    }

    @Test fun controlKeysEncodeClassicSequences() {
        val transport = FakeLocalPtyTransport()
        val session = LocalTerminalSession(transport, 80, 24) { false }
        session.sendKey(TerminalKey.ENTER)
        session.sendKey(TerminalKey.TAB)
        session.sendChar('c', ctrl = true) // Ctrl+C
        session.sendChar('d', ctrl = true) // Ctrl+D
        session.sendChar('z', ctrl = true) // Ctrl+Z
        session.sendKey(TerminalKey.ARROW_UP)
        Thread.sleep(30)
        assertEquals("\r\t\u0003\u0004\u001a\u001b[A", transport.writes.toString())
        session.close()
    }

    @Test fun interruptSendsCtrlC() {
        val transport = FakeLocalPtyTransport()
        val session = LocalTerminalSession(transport, 80, 24) { false }
        session.interrupt()
        Thread.sleep(30)
        assertEquals("\u0003", transport.writes.toString())
        session.close()
    }

    @Test fun resizeUpdatesScreenAndTransport() {
        val transport = FakeLocalPtyTransport()
        val session = LocalTerminalSession(transport, 80, 24) { false }
        session.resize(120, 40)
        assertEquals(120, session.screen.cols)
        assertEquals(40, session.screen.rows)
        assertEquals(120 to 40, transport.lastResize)
        session.close()
    }

    @Test fun readerParsesOutputThroughVt100Screen() {
        val transport = FakeLocalPtyTransport()
        val latch = CountDownLatch(1)
        val session = LocalTerminalSession(transport, 80, 24) { false }
        session.onOutput = { latch.countDown() }
        transport.feed("hello\r\nworld")
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val lines = session.screen.visibleLines()
        val text = lines.joinToString("") { row -> row.map { it.char }.joinToString("") }
        assertTrue(text.contains("hello"))
        session.close()
    }

    @Test fun closeMarksClosedAndClosesTransport() {
        val transport = FakeLocalPtyTransport()
        val session = LocalTerminalSession(transport, 80, 24) { false }
        session.close()
        Thread.sleep(30)
        assertEquals(LocalTerminalSession.State.CLOSED, session.state)
        assertTrue(transport.closed)
        assertFalse(session.isAlive())
    }

    @Test fun sendsAreIgnoredAfterClose() {
        val transport = FakeLocalPtyTransport()
        val session = LocalTerminalSession(transport, 80, 24) { false }
        session.close()
        Thread.sleep(30)
        session.sendInput("ignored\r")
        assertEquals("", transport.writes.toString())
    }
}
