package com.mobileharness.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class AdbConnectionTest {

    private val key by lazy { AdbKey.generate() }

    /** An InputStream that serves a fixed script of encoded ADB frames, then EOF. */
    private class ScriptedInput(private var frames: List<AdbProtocol.Message>) : InputStream() {
        private var buffer = ByteArray(0)
        private var pos = 0

        init {
            val out = ByteArrayOutputStream()
            frames.forEach { out.write(AdbProtocol.encode(it)) }
            buffer = out.toByteArray()
        }

        override fun read(): Int = if (pos >= buffer.size) -1 else buffer[pos++].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buffer.size) return -1
            val n = minOf(len, buffer.size - pos)
            System.arraycopy(buffer, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun conn(frames: List<AdbProtocol.Message>): AdbConnection =
        AdbConnection(ScriptedInput(frames), ByteArrayOutputStream())

    @Test
    fun `handshake answers auth token with signature and public key`() {
        val token = "0123456789abcdefghij".toByteArray()
        val output = ByteArrayOutputStream()
        val connection = AdbConnection(
            ScriptedInput(
                listOf(
                    AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token),
                    AdbProtocol.Message(AdbProtocol.A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, "device::".toByteArray()),
                ),
            ),
            output,
        )
        connection.connect(key)

        // Re-parse what the client wrote: CNXN, AUTH SIGNATURE, AUTH RSAPUBLICKEY.
        val written = ByteArrayInputStream(output.toByteArray())
        val cnxn = AdbProtocol.readMessage(written)
        assertEquals(AdbProtocol.A_CNXN, cnxn.command)

        val signature = AdbProtocol.readMessage(written)
        assertEquals(AdbProtocol.A_AUTH, signature.command)
        assertEquals(AdbProtocol.AUTH_SIGNATURE, signature.arg0)
        assertEquals(256, signature.payload.size) // RSA-2048 signature

        val publicKey = AdbProtocol.readMessage(written)
        assertEquals(AdbProtocol.A_AUTH, publicKey.command)
        assertEquals(AdbProtocol.AUTH_RSAPUBLICKEY, publicKey.arg0)
        assertTrue(publicKey.payload.isNotEmpty())
    }

    @Test
    fun `handshake without auth completes on immediate cnxn`() {
        val output = ByteArrayOutputStream()
        val connection = AdbConnection(
            ScriptedInput(
                listOf(AdbProtocol.Message(AdbProtocol.A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, "device::".toByteArray())),
            ),
            output,
        )
        connection.connect(key)
    }

    @Test
    fun `handshake rejects non-cnxn terminal frame`() {
        val output = ByteArrayOutputStream()
        val connection = AdbConnection(
            ScriptedInput(listOf(AdbProtocol.Message(AdbProtocol.A_CLSE, 0, 0, ByteArray(0)))),
            output,
        )
        assertThrows(AdbConnection.AdbException::class.java) { connection.connect(key) }
    }

    @Test
    fun `run shell captures stdout and stderr and stops on close`() {
        val output = ByteArrayOutputStream()
        val connection = AdbConnection(
            ScriptedInput(
                listOf(
                    AdbProtocol.Message(AdbProtocol.A_OKAY, 1, 1, ByteArray(0)),  // stdout remote id
                    AdbProtocol.Message(AdbProtocol.A_OKAY, 2, 2, ByteArray(0)),  // stderr remote id
                    AdbProtocol.Message(AdbProtocol.A_WRTE, 1, 1, "hello".toByteArray()),
                    AdbProtocol.Message(AdbProtocol.A_WRTE, 2, 2, "oops".toByteArray()),
                    AdbProtocol.Message(AdbProtocol.A_CLSE, 1, 1, ByteArray(0)),
                    AdbProtocol.Message(AdbProtocol.A_CLSE, 2, 2, ByteArray(0)),
                ),
            ),
            output,
        )
        val result = connection.runShell("echo hi", 4096)
        assertEquals("hello", result.stdout)
        assertEquals("oops", result.stderr)
    }

    @Test
    fun `run shell with no stderr stream terminates on stdout close`() {
        val connection = conn(
            listOf(
                AdbProtocol.Message(AdbProtocol.A_OKAY, 9, 1, ByteArray(0)),
                AdbProtocol.Message(AdbProtocol.A_WRTE, 9, 1, "out".toByteArray()),
                AdbProtocol.Message(AdbProtocol.A_CLSE, 9, 1, ByteArray(0)),
            ),
        )
        assertEquals("out", connection.runShell("id", 4096).stdout)
    }
}
