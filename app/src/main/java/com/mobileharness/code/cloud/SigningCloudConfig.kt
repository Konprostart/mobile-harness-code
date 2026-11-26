package com.mobileharness.code.cloud

import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-local configuration for automatic signing-key cloud backup.
 *
 * The `passphraseEnc` field holds only the AES-GCM ciphertext of the backup passphrase (produced by
 * the Android Keystore backed cipher); the plaintext passphrase is never persisted here and never
 * leaves the device. The whole config is device-local because the connection (a SAF tree URI) and
 * the passphrase ciphertext are meaningless on another phone — a new device re-connects and re-enters
 * the passphrase, then restores the keys from the cloud.
 */
data class SigningCloudConfig(
    val enabled: Boolean = false,
    val providerId: String = LocalDirectoryStorageProvider.PROVIDER_LOCAL,
    val connection: String = "",
    val keepVersions: Int = DEFAULT_KEEP_VERSIONS,
    val passphraseEnc: String = "",
    val lastBackupAt: Long = 0,
    val lastBackupKeyCount: Int = 0,
    val lastBackupFingerprints: List<String> = emptyList(),
    val lastBackupError: String = "",
    val lastRestoreAt: Long = 0,
) {
    val passphraseConfigured: Boolean get() = passphraseEnc.isNotBlank()

    companion object {
        const val DEFAULT_KEEP_VERSIONS = 5
        const val MIN_KEEP_VERSIONS = 1
        const val MAX_KEEP_VERSIONS = 30

        fun fromJson(json: String): SigningCloudConfig = runCatching {
            val o = JSONObject(json)
            val keep = o.optInt("keepVersions", DEFAULT_KEEP_VERSIONS)
                .coerceIn(MIN_KEEP_VERSIONS, MAX_KEEP_VERSIONS)
            val fingerprints = o.optJSONArray("lastBackupFingerprints")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList()
            SigningCloudConfig(
                enabled = o.optBoolean("enabled"),
                providerId = o.optString("providerId").ifBlank { LocalDirectoryStorageProvider.PROVIDER_LOCAL },
                connection = o.optString("connection"),
                keepVersions = keep,
                passphraseEnc = o.optString("passphraseEnc"),
                lastBackupAt = o.optLong("lastBackupAt"),
                lastBackupKeyCount = o.optInt("lastBackupKeyCount"),
                lastBackupFingerprints = fingerprints,
                lastBackupError = o.optString("lastBackupError"),
                lastRestoreAt = o.optLong("lastRestoreAt"),
            )
        }.getOrDefault(SigningCloudConfig())

        fun toJson(config: SigningCloudConfig): String = JSONObject().apply {
            put("enabled", config.enabled)
            put("providerId", config.providerId)
            put("connection", config.connection)
            put("keepVersions", config.keepVersions)
            put("passphraseEnc", config.passphraseEnc)
            put("lastBackupAt", config.lastBackupAt)
            put("lastBackupKeyCount", config.lastBackupKeyCount)
            put("lastBackupFingerprints", JSONArray(config.lastBackupFingerprints))
            put("lastBackupError", config.lastBackupError)
            put("lastRestoreAt", config.lastRestoreAt)
        }.toString()
    }
}
