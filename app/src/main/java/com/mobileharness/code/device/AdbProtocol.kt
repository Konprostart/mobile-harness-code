package com.mobileharness.code.device

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Pure ADB wire protocol: message framing plus the connection handshake state machine used to open
 * a shell over the Wireless Debugging transport. This is the documented `protocol.txt` format (the
 * same one `adbd` speaks), implemented without an external dependency so it stays unit-testable and
 * license-clean (AOSP is Apache-2.0; this is an independent implementation of the public protocol).
 *
 * The SPAKE2 *pairing* exchange that provisions the first ADB key is intentionally out of scope for
 * this executor (see [AdbPairing]; it only accepts an already-paired key). Execution is fail-closed:
 * any framing error, auth rejection or closed stream surfaces as a clean error, never as success.
 */
object AdbProtocol {
    // Command bytes (stored little-endian on the wire).
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257

    // A_AUTH arg0 values.
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val VERSION = 0x01000000
    const val MAX_PAYLOAD = 4096

    /** 24-byte header followed by [dataLength] payload bytes. */
    data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Message && command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * (31 * (31 * command + arg0) + arg1) + payload.contentHashCode()
    }

    fun encode(m: Message): ByteArray {
        val out = ByteArray(24 + m.payload.size)
        writeIntLE(out, 0, m.command)
        writeIntLE(out, 4, m.arg0)
        writeIntLE(out, 8, m.arg1)
        writeIntLE(out, 12, m.payload.size)
        writeIntLE(out, 16, crc32(m.payload))
        writeIntLE(out, 20, m.command.inv())
        m.payload.copyInto(out, 24)
        return out
    }

    /** Reads one complete frame; throws [EOFException] on a clean stream end, [IOException] on a bad frame. */
    fun readMessage(input: InputStream): Message {
        val header = ByteArray(24)
        var off = 0
        while (off < header.size) {
            val n = input.read(header, off, header.size - off)
            if (n < 0) throw EOFException("stream closed")
            off += n
        }
        val command = readIntLE(header, 0)
        val arg0 = readIntLE(header, 4)
        val arg1 = readIntLE(header, 8)
        val dataLength = readIntLE(header, 12)
        val dataCrc = readIntLE(header, 16)
        val magic = readIntLE(header, 20)
        if (magic != command.inv()) throw java.io.IOException("bad adb frame magic")
        if (dataLength < 0 || dataLength > 64 * 1024 * 1024) throw java.io.IOException("bad adb payload length $dataLength")
        val payload = ByteArray(dataLength)
        var p = 0
        while (p < dataLength) {
            val n = input.read(payload, p, dataLength - p)
            if (n < 0) throw EOFException("stream closed mid-payload")
            p += n
        }
        if (dataLength > 0 && crc32(payload) != dataCrc) throw java.io.IOException("bad adb payload crc")
        return Message(command, arg0, arg1, payload)
    }

    fun writeMessage(output: OutputStream, m: Message) {
        output.write(encode(m))
        output.flush()
    }

    // Helpers ---------------------------------------------------------------

    private fun readIntLE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or
            ((b[offset + 1].toInt() and 0xff) shl 8) or
            ((b[offset + 2].toInt() and 0xff) shl 16) or
            ((b[offset + 3].toInt() and 0xff) shl 24)

    private fun writeIntLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xff).toByte()
        b[offset + 1] = ((value ushr 8) and 0xff).toByte()
        b[offset + 2] = ((value ushr 16) and 0xff).toByte()
        b[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun crc32(data: ByteArray): Int =
        java.util.zip.CRC32().apply { update(data) }.value.toInt()

    /** Computes the SHA-1 of [data] (ADB signs SHA1(token)). */
    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
}
