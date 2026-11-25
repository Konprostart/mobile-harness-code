package com.mobileharness.code.grom

import app.grom.sdk.connect.GromSigner
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The host's Ed25519 identity for key-based GROM auth (S6-R012).
 *
 * The SDK (`GromSigner`) never generates, stores or exports a key; this factory
 * does the generation and byte/hex helpers, and the *caller* decides where the
 * private key is stored (in this app: the AES-GCM `ApiKeyVault`). Nothing here
 * hardcodes or logs a key.
 */
object GromSignerFactory {
    const val ALGORITHM: String = "Ed25519"

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(ALGORITHM).run {
            initialize(255)
            generateKeyPair()
        }

    /** `sha256(publicKey)[0..16]` — the `x-grom-key` header value (S6-R012). */
    fun keyId(publicKey: PublicKey): String = sha256(publicKey.encoded).take(16)

    fun publicKeyBase64(publicKey: PublicKey): String =
        Base64.getEncoder().encodeToString(publicKey.encoded)

    fun privateKeyBase64(privateKey: PrivateKey): String =
        Base64.getEncoder().encodeToString(privateKey.encoded)

    fun signer(privateKey: PrivateKey, keyId: String): GromSigner = object : GromSigner {
        override val keyId: String = keyId
        override fun sign(message: ByteArray): ByteArray =
            Signature.getInstance(ALGORITHM).run {
                initSign(privateKey)
                update(message)
                sign()
            }
    }

    fun parsePrivateKey(base64: String): PrivateKey? = runCatching {
        KeyFactory.getInstance(ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(hex[value ushr 4]).append(hex[value and 0x0f])
        }
        return out.toString()
    }
}
