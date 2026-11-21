package com.mobileharness.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class AdbKeyTest {

    private val pair by lazy { AdbKey.generate() }

    @Test
    fun `generated key is a 2048 bit RSA pair`() {
        val rsa = pair.publicKey as java.security.interfaces.RSAPublicKey
        assertEquals(2048, rsa.modulus.bitLength())
    }

    @Test
    fun `public key blob has ssh-rsa structure`() {
        val blob = AdbKey.publicKeyBlob(pair.publicKey)
        // [4-byte BE len]["ssh-rsa"][4-byte BE len][e][4-byte BE len][n]
        val len = beInt(blob, 0)
        assertEquals("ssh-rsa", String(blob, 4, len, Charsets.US_ASCII))
        assertTrue(blob.size > 4 + len + 8)
    }

    @Test
    fun `sign token produces a verifiable sha1 rsa signature`() {
        val token = "abcdefghijklmnopqrst".toByteArray()
        val sig = AdbKey.signToken(pair.privateKey, token)
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(pair.publicKey)
        verifier.update(token)
        assertTrue(verifier.verify(sig))
    }

    @Test
    fun `rsapublickey data uses little-endian length prefix`() {
        val data = AdbKey.rsapublickeyData(pair.publicKey, "u@h")
        val len = (data[0].toInt() and 0xff) or
            ((data[1].toInt() and 0xff) shl 8) or
            ((data[2].toInt() and 0xff) shl 16) or
            ((data[3].toInt() and 0xff) shl 24)
        assertEquals(data.size - 4, len)
        val line = String(data, 4, len, Charsets.US_ASCII)
        assertTrue(line.endsWith(" u@h"))
        val b64 = line.substringBefore(' ')
        // Base64 decodes cleanly and matches the public key blob.
        assertEquals(
            AdbKey.publicKeyBlob(pair.publicKey).toList(),
            Base64.getDecoder().decode(b64).toList(),
        )
    }

    @Test
    fun `public key line is adbkey pub format`() {
        val line = AdbKey.publicKeyLine(pair.publicKey, "mobile@harness")
        assertTrue(line.endsWith(" mobile@harness"))
        assertTrue(line.substringBefore(' ').isNotBlank())
    }

    @Test
    fun `blob decodes back to same modulus and exponent`() {
        val blob = AdbKey.publicKeyBlob(pair.publicKey)
        // Parse: [len]ssh-rsa[len]e[len]n
        var off = 0
        val sshRsaLen = beInt(blob, off); off += 4 + sshRsaLen
        val eLen = beInt(blob, off); off += 4
        val e = java.math.BigInteger(blob.copyOfRange(off, off + eLen)); off += eLen
        val nLen = beInt(blob, off); off += 4
        val n = java.math.BigInteger(blob.copyOfRange(off, off + nLen))
        val spec = RSAPublicKeySpec(n, e)
        val rebuilt = KeyFactory.getInstance("RSA").generatePublic(spec) as java.security.interfaces.RSAPublicKey
        assertEquals((pair.publicKey as java.security.interfaces.RSAPublicKey).modulus, rebuilt.modulus)
        assertEquals((pair.publicKey as java.security.interfaces.RSAPublicKey).publicExponent, rebuilt.publicExponent)
    }

    private fun beInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xff) shl 24) or
            ((b[offset + 1].toInt() and 0xff) shl 16) or
            ((b[offset + 2].toInt() and 0xff) shl 8) or
            (b[offset + 3].toInt() and 0xff)
}
