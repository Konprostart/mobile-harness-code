package com.mobileharness.code.grom

import app.grom.sdk.connect.GromConnectClient
import app.grom.sdk.connect.GromCredential
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GromSignerFactoryTest {

    @Test
    fun keyIdIs16LowercaseHexOfSha256() {
        val pair = GromSignerFactory.generateKeyPair()
        val keyId = GromSignerFactory.keyId(pair.public)
        assertEquals(16, keyId.length)
        assertTrue(keyId.all { it in "0123456789abcdef" })
        assertEquals(sha256(pair.public.encoded).take(16), keyId)
    }

    @Test
    fun privateKeyBase64RoundTrips() {
        val pair = GromSignerFactory.generateKeyPair()
        val encoded = GromSignerFactory.privateKeyBase64(pair.private)
        val parsed = GromSignerFactory.parsePrivateKey(encoded)
        assertNotNull(parsed)
        assertEquals(pair.private.encoded.toList(), parsed!!.encoded.toList())
    }

    @Test
    fun signerProduces64ByteSignatures() {
        val pair = GromSignerFactory.generateKeyPair()
        val keyId = GromSignerFactory.keyId(pair.public)
        val signer = GromSignerFactory.signer(pair.private, keyId)
        val signature = signer.sign("hello".toByteArray())
        assertEquals(64, signature.size)
    }

    @Test
    fun clientSendsIndependentlyVerifiableSignature() {
        // This pins the wire format independently of the SDK's own canonical
        // string: we re-derive the signed string from the spec (S6-R012) and
        // verify it with the public key, so the client and server cannot drift.
        val pair = GromSignerFactory.generateKeyPair()
        val keyId = GromSignerFactory.keyId(pair.public)
        val signer = GromSignerFactory.signer(pair.private, keyId)

        val transport = FakeGromTransport(FakeGromDataPlane())
        val client = GromConnectClient("http://127.0.0.1:47002", transport = transport)

        client.connect(GromCredential.Key(signer))

        val request = transport.requests.last()
        val headers = request.headers
        assertEquals(keyId, headers["x-grom-key"])
        val timestamp = headers.getValue("x-grom-timestamp")
        val nonce = headers.getValue("x-grom-nonce")
        val signature = Base64.getDecoder().decode(headers.getValue("x-grom-signature"))
        assertEquals(64, signature.size)

        val canonical = canonicalString(request.method, "/grom/v1/connect", request.body, timestamp, nonce)
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(pair.public)
        verifier.update(canonical.toByteArray(Charsets.UTF_8))
        assertTrue("signature must verify against the independent canonical string", verifier.verify(signature))
    }

    private fun canonicalString(method: String, path: String, body: String, timestamp: String, nonce: String): String =
        "grom-ed25519-v1\n" +
            method.uppercase() + "\n" +
            path + "\n" +
            timestamp + "\n" +
            nonce + "\n" +
            sha256(body.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]).append(hex[value and 0x0f])
            }
        }
    }
}
