package com.mobileharness.code.network.ssh

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal key-value storage abstraction so [SshSecretStore] can be unit-tested against an
 * in-memory map instead of Android [SharedPreferences].
 */
interface SshSecretStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * AES-GCM envelope over exact plaintext bytes. The cipher must never normalize the input: an
 * SSH private key is a multiline PEM document and a passphrase may contain spaces, so byte
 * fidelity is the whole point (unlike the API-key vault's whitespace-stripping `cleanSecret`).
 */
interface SshSecretCipher {
    data class Encrypted(val ivBase64: String, val ciphertextBase64: String)
    fun encrypt(plainBytes: ByteArray): Encrypted
    fun decrypt(ivBase64: String, ciphertextBase64: String): ByteArray
}

/**
 * Opaque, encrypted storage for SSH credentials and the pinned host-key fingerprint.
 *
 * Unlike [com.mobileharness.code.data.ApiKeyVault], there is deliberately NO whitespace or
 * "Bearer " cleaning: [SshSecretStore.Slot.PRIVATE_KEY] and [SshSecretStore.Slot.PASSPHRASE]
 * must round-trip exactly (multiline PEM, embedded spaces). Secrets are device-only — they are
 * held in a dedicated preferences file that the in-app Backup & Restore never reads, and the
 * whole app is already excluded from Android cloud backup.
 */
class SshSecretStore(
    private val storage: SshSecretStorage,
    private val cipher: SshSecretCipher,
) {
    enum class Slot { PASSWORD, PRIVATE_KEY, PASSPHRASE, HOST_KEY }

    /** Encrypts and stores a secret under `profileId` + [slot]. Empty values are stored as-is (never rejected). */
    @Synchronized
    fun put(profileId: String, slot: Slot, secret: String) {
        val encrypted = cipher.encrypt(secret.toByteArray(Charsets.UTF_8))
        storage.putString(ivKey(profileId, slot), encrypted.ivBase64)
        storage.putString(valueKey(profileId, slot), encrypted.ciphertextBase64)
    }

    /** Decrypts and returns a secret, or null when absent or undecryptable (fail-closed). */
    @Synchronized
    fun get(profileId: String, slot: Slot): String? {
        val iv = storage.getString(ivKey(profileId, slot)) ?: return null
        val value = storage.getString(valueKey(profileId, slot)) ?: return null
        return runCatching { cipher.decrypt(iv, value).toString(Charsets.UTF_8) }.getOrNull()
    }

    /** Whether a slot holds a stored value (used to show "configured" without exposing it). */
    @Synchronized
    fun has(profileId: String, slot: Slot): Boolean =
        storage.getString(valueKey(profileId, slot)) != null

    /** Removes every slot for a profile (used when a profile is deleted). */
    @Synchronized
    fun remove(profileId: String) {
        Slot.entries.forEach { slot ->
            storage.remove(ivKey(profileId, slot))
            storage.remove(valueKey(profileId, slot))
        }
    }

    private fun ivKey(profileId: String, slot: Slot) = "$profileId.${slot.name}.iv"
    private fun valueKey(profileId: String, slot: Slot) = "$profileId.${slot.name}.value"
}

/** [SshSecretStorage] backed by a dedicated [SharedPreferences] file. */
class SharedPrefsSshSecretStorage(private val prefs: SharedPreferences) : SshSecretStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/** [SshSecretCipher] backed by the Android Keystore AES-GCM master key. */
class AndroidSshSecretCipher(context: Context) : SshSecretCipher {
    private val alias = "ssh-secret-key"

    override fun encrypt(plainBytes: ByteArray): SshSecretCipher.Encrypted {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainBytes)
        return SshSecretCipher.Encrypted(
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
        )
    }

    override fun decrypt(ivBase64: String, ciphertextBase64: String): ByteArray {
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val encrypted = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}

/** Production factory: an opaque, Keystore-backed [SshSecretStore] for the app process. */
fun sshSecretStore(context: Context): SshSecretStore = SshSecretStore(
    SharedPrefsSshSecretStorage(context.getSharedPreferences("ssh_secrets", Context.MODE_PRIVATE)),
    AndroidSshSecretCipher(context),
)
