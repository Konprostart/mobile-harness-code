package com.mobileharness.code.device

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * ADB RSA key handling: key generation, the SSH-RSA style public-key blob, the `adbkey.pub` /
 * `A_AUTH RSAPUBLICKEY` wire encoding, and the `SHA1(token)` signature used during authentication.
 *
 * The key is a plain 2048-bit RSA pair (ADB has no hardware-key requirement); the Android layer is
 * responsible for storing it in app-private storage with owner-only permissions. The public-key
 * format mirrors `adb_auth` from AOSP (Apache-2.0); this is an independent implementation.
 */
object AdbKey {
    private const val KEY_SIZE = 2048
    private const val KEY_ALGORITHM = "RSA"
    private const val SIGNATURE_ALGORITHM = "SHA1withRSA"
    private const val SSH_RSA = "ssh-rsa"

    data class Pair(
        val privateKey: java.security.PrivateKey,
        val publicKey: java.security.PublicKey,
    )

    fun generate(): Pair {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(KEY_SIZE)
        val kp = generator.generateKeyPair()
        return Pair(kp.private, kp.public)
    }

    /**
     * The SSH-RSA public-key blob: `[len]ssh-rsa[len]e[len]n` with 4-byte big-endian lengths,
     * as used by `adbkey.pub`.
     */
    fun publicKeyBlob(publicKey: java.security.PublicKey): ByteArray {
        val rsa = publicKey as? RSAPublicKey ?: throw IllegalArgumentException("not an RSA key")
        return buildBlob(rsa.publicExponent, rsa.modulus)
    }

    /** The full `adbkey.pub` line: base64(blob) + " user@host". */
    fun publicKeyLine(publicKey: java.security.PublicKey, userHost: String = "unknown@unknown"): String =
        Base64.getEncoder().encodeToString(publicKeyBlob(publicKey)) + " $userHost"

    /**
     * The `A_AUTH RSAPUBLICKEY` payload: `[4-byte LE length][base64(blob)][userHost]` (no trailing
     * newline). ADB uses a little-endian length here, unlike the big-endian lengths inside the blob.
     */
    fun rsapublickeyData(publicKey: java.security.PublicKey, userHost: String = "unknown@unknown"): ByteArray {
        val line = Base64.getEncoder().encodeToString(publicKeyBlob(publicKey)) + " $userHost"
        val lineBytes = line.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(4 + lineBytes.size)
        val len = lineBytes.size
        out[0] = (len and 0xff).toByte()
        out[1] = ((len ushr 8) and 0xff).toByte()
        out[2] = ((len ushr 16) and 0xff).toByte()
        out[3] = ((len ushr 24) and 0xff).toByte()
        lineBytes.copyInto(out, 4)
        return out
    }

    /** Signs the 20-byte auth token with SHA1withRSA (ADB's A_AUTH SIGNATURE payload). */
    fun signToken(privateKey: java.security.PrivateKey, token: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(token)
        return signature.sign()
    }

    private fun buildBlob(exponent: BigInteger, modulus: BigInteger): ByteArray {
        val eBytes = exponent.toByteArray()
        val nBytes = modulus.toByteArray()
        val sshRsa = SSH_RSA.toByteArray(Charsets.US_ASCII)
        val total = 4 + sshRsa.size + 4 + eBytes.size + 4 + nBytes.size
        val out = ByteArray(total)
        var off = 0
        off = putLen(out, off, sshRsa)
        off = putLen(out, off, eBytes)
        putLen(out, off, nBytes)
        return out
    }

    private fun putLen(out: ByteArray, offset: Int, value: ByteArray): Int {
        writeIntBE(out, offset, value.size)
        value.copyInto(out, offset + 4)
        return offset + 4 + value.size
    }

    private fun writeIntBE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = ((value ushr 24) and 0xff).toByte()
        b[offset + 1] = ((value ushr 16) and 0xff).toByte()
        b[offset + 2] = ((value ushr 8) and 0xff).toByte()
        b[offset + 3] = (value and 0xff).toByte()
    }
}
