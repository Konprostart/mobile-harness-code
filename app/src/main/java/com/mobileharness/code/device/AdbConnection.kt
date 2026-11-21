package com.mobileharness.code.device

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Pure ADB connection: performs the CNXN/AUTH handshake over already-connected streams and then
 * runs a single `shell:<command>`. This is the transport half of [WirelessAdbExecutor]; it accepts
 * an [AdbKey.Pair] that must already be paired with the target `adbd` (first-time SPAKE2 pairing is
 * documented in [AdbPairing]). Fail-closed: any unexpected frame or auth rejection throws.
 */
class AdbConnection(
    private val input: InputStream,
    private val output: OutputStream,
) {
    class AdbException(message: String) : Exception(message)

    data class ShellResult(val stdout: String, val stderr: String)

    /**
     * Handshake: send CNXN, answer AUTH TOKEN with a signature + public key, and expect a final CNXN.
     * Throws [AdbException] when `adbd` rejects the key (pairing required / key not authorized).
     */
    fun connect(key: AdbKey.Pair, userHost: String = "unknown@unknown") {
        AdbProtocol.writeMessage(
            output,
            AdbProtocol.Message(
                AdbProtocol.A_CNXN,
                AdbProtocol.VERSION,
                AdbProtocol.MAX_PAYLOAD,
                "host::".toByteArray(Charsets.US_ASCII),
            ),
        )
        var msg = AdbProtocol.readMessage(input)
        var authAttempts = 0
        while (msg.command == AdbProtocol.A_AUTH) {
            if (++authAttempts > 4) throw AdbException("too many adb auth rounds")
            if (msg.arg0 == AdbProtocol.AUTH_TOKEN) {
                val signature = AdbKey.signToken(key.privateKey, msg.payload)
                AdbProtocol.writeMessage(
                    output,
                    AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signature),
                )
                AdbProtocol.writeMessage(
                    output,
                    AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, AdbKey.rsapublickeyData(key.publicKey, userHost)),
                )
            }
            msg = AdbProtocol.readMessage(input)
        }
        if (msg.command != AdbProtocol.A_CNXN) {
            throw AdbException("unexpected adb frame during handshake (0x%08x)".format(msg.command))
        }
    }

    /**
     * Runs `shell:<command>` and returns its captured stdout/stderr. Output is capped at
     * [maxOutputBytes] per stream; a silent read is bounded by the caller's socket read timeout.
     */
    fun runShell(command: String, maxOutputBytes: Int): ShellResult {
        val localStdout = 1
        val localStderr = 2
        AdbProtocol.writeMessage(
            output,
            AdbProtocol.Message(AdbProtocol.A_OPEN, localStdout, 0, "shell:$command".toByteArray(Charsets.UTF_8)),
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var closedStdout = false
        var closedStderr = false
        // adbd streams stderr on a second local id; tolerate a server that only opens stdout.
        var stderrSeen = false
        while (!(closedStdout && (closedStderr || !stderrSeen))) {
            val m = AdbProtocol.readMessage(input)
            when (m.command) {
                AdbProtocol.A_OKAY -> if (m.arg1 == localStderr) stderrSeen = true
                AdbProtocol.A_WRTE -> {
                    if (m.arg1 == localStdout) {
                        stdout.write(m.payload, 0, minOf(m.payload.size, maxOutputBytes - stdout.size()))
                    } else if (m.arg1 == localStderr) {
                        stderrSeen = true
                        stderr.write(m.payload, 0, minOf(m.payload.size, maxOutputBytes - stderr.size()))
                    }
                }
                AdbProtocol.A_CLSE -> {
                    if (m.arg1 == localStdout) closedStdout = true
                    if (m.arg1 == localStderr) closedStderr = true
                    // If stderr was never opened, consider it closed so we don't loop forever.
                    if (m.arg1 == localStdout && !stderrSeen) closedStderr = true
                }
            }
        }
        return ShellResult(stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }
}
