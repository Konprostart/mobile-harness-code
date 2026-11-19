package com.mobileharness.code.network.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Plain-JCE AES-GCM cipher so the opaque store is testable without Android Keystore. */
private class JceSshSecretCipher(private val key: SecretKey) : SshSecretCipher {
    override fun encrypt(plainBytes: ByteArray): SshSecretCipher.Encrypted {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plainBytes)
        return SshSecretCipher.Encrypted(
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(encrypted),
        )
    }

    override fun decrypt(ivBase64: String, ciphertextBase64: String): ByteArray {
        val iv = Base64.getDecoder().decode(ivBase64)
        val encrypted = Base64.getDecoder().decode(ciphertextBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}

private class InMemorySshSecretStorage : SshSecretStorage {
    val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

class SshSecretStoreTest {
    private fun store(): SshSecretStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return SshSecretStore(InMemorySshSecretStorage(), JceSshSecretCipher(key))
    }

    @Test
    fun multilinePrivateKeyRoundTripsExactly() {
        val store = store()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "b3BlbnNzaC1rZXktdjEAAAAA\n" +
            "-----END OPENSSH PRIVATE KEY-----\n"
        store.put("p1", SshSecretStore.Slot.PRIVATE_KEY, pem)
        assertEquals(pem, store.get("p1", SshSecretStore.Slot.PRIVATE_KEY))
    }

    @Test
    fun passphraseWithSpacesRoundTripsExactly() {
        val store = store()
        val passphrase = "correct horse battery staple  "
        store.put("p1", SshSecretStore.Slot.PASSPHRASE, passphrase)
        assertEquals(passphrase, store.get("p1", SshSecretStore.Slot.PASSPHRASE))
    }

    @Test
    fun emptySecretRoundTripsAndIsPresent() {
        val store = store()
        store.put("p1", SshSecretStore.Slot.PASSWORD, "")
        assertEquals("", store.get("p1", SshSecretStore.Slot.PASSWORD))
        assertTrue(store.has("p1", SshSecretStore.Slot.PASSWORD))
    }

    @Test
    fun missingSlotReturnsNullAndIsAbsent() {
        val store = store()
        assertNull(store.get("p1", SshSecretStore.Slot.PASSWORD))
        assertFalse(store.has("p1", SshSecretStore.Slot.PASSWORD))
    }

    @Test
    fun removeClearsEverySlotForAProfile() {
        val store = store()
        store.put("p1", SshSecretStore.Slot.PASSWORD, "pw")
        store.put("p1", SshSecretStore.Slot.HOST_KEY, "SHA256:x")
        store.remove("p1")
        assertNull(store.get("p1", SshSecretStore.Slot.PASSWORD))
        assertNull(store.get("p1", SshSecretStore.Slot.HOST_KEY))
    }

    @Test
    fun profilesDoNotLeakIntoEachOther() {
        val store = store()
        store.put("p1", SshSecretStore.Slot.PASSWORD, "one")
        store.put("p2", SshSecretStore.Slot.PASSWORD, "two")
        assertEquals("one", store.get("p1", SshSecretStore.Slot.PASSWORD))
        assertEquals("two", store.get("p2", SshSecretStore.Slot.PASSWORD))
    }
}
