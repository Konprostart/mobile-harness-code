package com.mobileharness.code.network.ssh

import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePtyTransport : SshPtyTransport {
    @Volatile
    override var isOpen: Boolean = true
        private set

    private val written = Collections.synchronizedList(mutableListOf<ByteArray>())
    private val resized = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
    private val pipeIn = PipedInputStream()
    private val pipeOut = PipedOutputStream(pipeIn)

    @Volatile
    var closeCount = 0
        private set

    override fun inputStream(): InputStream = pipeIn

    override fun write(bytes: ByteArray) {
        written.add(bytes.copyOf())
    }

    override fun resize(cols: Int, rows: Int) {
        resized.add(cols to rows)
    }

    override fun close() {
        closeCount++
        isOpen = false
        runCatching { pipeOut.close() }
        runCatching { pipeIn.close() }
    }

    fun feed(text: String) {
        runCatching {
            pipeOut.write(text.toByteArray(Charsets.UTF_8))
            pipeOut.flush()
        }
    }

    val writtenText: String
        get() = synchronized(written) { written.joinToString("") { String(it, Charsets.UTF_8) } }

    val lastResize: Pair<Int, Int>?
        get() = synchronized(resized) { resized.lastOrNull() }
}

class SshShellTest {
    @Test
    fun controlBytesEncodeClassicSequences() {
        assertEquals("\r", SshShell.controlBytes(SshControlKey.ENTER))
        assertEquals("\t", SshShell.controlBytes(SshControlKey.TAB))
        assertEquals("\u001b", SshShell.controlBytes(SshControlKey.ESC))
        assertEquals("\u0003", SshShell.controlBytes(SshControlKey.CTRL_C))
        assertEquals("\u0004", SshShell.controlBytes(SshControlKey.CTRL_D))
        assertEquals("\u001a", SshShell.controlBytes(SshControlKey.CTRL_Z))
        assertEquals("\u007f", SshShell.controlBytes(SshControlKey.BACKSPACE))
        assertEquals("\u001b[A", SshShell.controlBytes(SshControlKey.ARROW_UP))
        assertEquals("\u001b[B", SshShell.controlBytes(SshControlKey.ARROW_DOWN))
        assertEquals("\u001b[D", SshShell.controlBytes(SshControlKey.ARROW_LEFT))
        assertEquals("\u001b[C", SshShell.controlBytes(SshControlKey.ARROW_RIGHT))
    }

    @Test
    fun sendInputWritesUtf8Bytes() {
        val transport = FakePtyTransport()
        val session = SshShellSession(transport, 10, 3) { false }
        session.sendInput("ls -la\r")
        assertEquals("ls -la\r", transport.writtenText)
        session.close()
    }

    @Test
    fun sendAfterCloseIsIgnored() {
        val transport = FakePtyTransport()
        val session = SshShellSession(transport, 10, 3) { false }
        session.close()
        session.sendInput("ignored")
        assertEquals("", transport.writtenText)
        assertEquals(SshShellSession.State.CLOSED, session.state)
    }

    @Test
    fun closeIsIdempotent() {
        val transport = FakePtyTransport()
        val session = SshShellSession(transport, 10, 3) { false }
        session.close()
        session.close()
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun resizeUpdatesScreenAndTransport() {
        val transport = FakePtyTransport()
        val session = SshShellSession(transport, 10, 3) { false }
        session.resize(40, 12)
        assertEquals(40, session.screen.cols)
        assertEquals(12, session.screen.rows)
        assertEquals(40 to 12, transport.lastResize)
        session.close()
    }

    @Test
    fun resizeAfterCloseDoesNotTouchTransport() {
        val transport = FakePtyTransport()
        val session = SshShellSession(transport, 10, 3) { false }
        session.close()
        session.resize(99, 99)
        assertEquals(null, transport.lastResize)
    }

    @Test
    fun readerParsesRemoteOutputIntoScreenAndNotifies() {
        val transport = FakePtyTransport()
        val session = SshShellSession(transport, 20, 4) { false }
        val latch = CountDownLatch(1)
        session.onOutput = { latch.countDown() }
        transport.feed("hello\r\nworld")
        assertTrue("reader did not emit output", latch.await(3, TimeUnit.SECONDS))
        // Give the emulator a moment to finish the (already-notified) write.
        val lines = session.screen.screenLines().map { row -> row.map { it.char }.joinToString("").trimEnd() }
        assertTrue(lines.joinToString("\n").contains("hello"))
        assertTrue(lines.joinToString("\n").contains("world"))
        session.close()
    }

    @Test
    fun ecoScrollbackBudgetIsSmallerThanNormal() {
        assertTrue(SshShell.MAX_SCROLLBACK_LINES_ECO < SshShell.MAX_SCROLLBACK_LINES)
        val eco = SshShellSession(FakePtyTransport(), 10, 3) { true }
        assertEquals(0, eco.screen.scrollbackSize())
        eco.close()
    }
}
