package com.mobileharness.code.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class AdbProtocolTest {

    @Test
    fun `message round trips through encode and read`() {
        val m = AdbProtocol.Message(AdbProtocol.A_CNXN, 0x01000000, 4096, "host::".toByteArray())
        val encoded = AdbProtocol.encode(m)
        val decoded = AdbProtocol.readMessage(ByteArrayInputStream(encoded))
        assertEquals(m, decoded)
    }

    @Test
    fun `frame header is 24 bytes plus payload`() {
        val m = AdbProtocol.Message(AdbProtocol.A_OPEN, 1, 0, "shell:id".toByteArray())
        assertEquals(24 + "shell:id".length, AdbProtocol.encode(m).size)
    }

    @Test
    fun `corrupt magic is rejected`() {
        val m = AdbProtocol.Message(AdbProtocol.A_CNXN, 1, 2, byteArrayOf(1, 2, 3))
        val encoded = AdbProtocol.encode(m)
        // Flip the magic bytes (offset 20..23).
        encoded[20] = (encoded[20].toInt() xor 0xff).toByte()
        assertThrows(IOException::class.java) { AdbProtocol.readMessage(ByteArrayInputStream(encoded)) }
    }

    @Test
    fun `corrupt payload crc is rejected`() {
        val m = AdbProtocol.Message(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(9, 9, 9, 9))
        val encoded = AdbProtocol.encode(m)
        encoded[24] = 0x42 // tamper with payload byte, CRC mismatch
        assertThrows(IOException::class.java) { AdbProtocol.readMessage(ByteArrayInputStream(encoded)) }
    }

    @Test
    fun `empty stream raises EOF`() {
        assertThrows(EOFException::class.java) {
            AdbProtocol.readMessage(ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Test
    fun `write message flushes to output`() {
        val out = ByteArrayOutputStream()
        AdbProtocol.writeMessage(out, AdbProtocol.Message(AdbProtocol.A_OKAY, 7, 1, ByteArray(0)))
        assertTrue(out.size() == 24)
    }

    @Test
    fun `sha1 of token is stable`() {
        val token = "0123456789abcdefghij".toByteArray()
        val sha = AdbProtocol.sha1(token)
        assertEquals(20, sha.size)
        assertArrayEquals(sha, AdbProtocol.sha1(token))
    }
}
