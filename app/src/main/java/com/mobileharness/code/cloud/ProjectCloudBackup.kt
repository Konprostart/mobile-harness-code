package com.mobileharness.code.cloud

import org.json.JSONObject

/**
 * Per-project cloud backup configuration.
 *
 * This is the per-project analogue of [SigningCloudConfig]: every project independently chooses
 * whether (and where) its source + Git history + bound signing keys are backed up. A project may
 * use Google Drive while another uses a different provider, and a third may have no cloud backup.
 *
 * Like [SigningCloudConfig], this config is device-local: `connection` (a SAF tree URI) and the
 * AES-GCM ciphertext of the backup passphrase are meaningless on another phone, so a new device
 * re-connects and re-enters the passphrase before restoring.
 */
data class ProjectCloudBackup(
    val enabled: Boolean = false,
    val providerId: String = LocalDirectoryStorageProvider.PROVIDER_LOCAL,
    val connection: String = "",
    val keepVersions: Int = SigningCloudConfig.DEFAULT_KEEP_VERSIONS,
    val passphraseEnc: String = "",
    val autoBackup: Boolean = false,
    val lastBackupAt: Long = 0,
    val lastBackupFingerprints: List<String> = emptyList(),
    val lastBackupError: String = "",
    val lastRestoreAt: Long = 0,
) {
    val passphraseConfigured: Boolean get() = passphraseEnc.isNotBlank()

    companion object {
        fun fromJson(json: String): ProjectCloudBackup = runCatching {
            val o = JSONObject(json)
            val keep = o.optInt("keepVersions", SigningCloudConfig.DEFAULT_KEEP_VERSIONS)
                .coerceIn(SigningCloudConfig.MIN_KEEP_VERSIONS, SigningCloudConfig.MAX_KEEP_VERSIONS)
            ProjectCloudBackup(
                enabled = o.optBoolean("enabled"),
                providerId = o.optString("providerId").ifBlank { LocalDirectoryStorageProvider.PROVIDER_LOCAL },
                connection = o.optString("connection"),
                keepVersions = keep,
                passphraseEnc = o.optString("passphraseEnc"),
                autoBackup = o.optBoolean("autoBackup"),
                lastBackupAt = o.optLong("lastBackupAt"),
                lastBackupFingerprints = o.optJSONArray("lastBackupFingerprints")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList(),
                lastBackupError = o.optString("lastBackupError"),
                lastRestoreAt = o.optLong("lastRestoreAt"),
            )
        }.getOrDefault(ProjectCloudBackup())

        fun toJson(config: ProjectCloudBackup): String = JSONObject().apply {
            put("enabled", config.enabled)
            put("providerId", config.providerId)
            put("connection", config.connection)
            put("keepVersions", config.keepVersions)
            put("passphraseEnc", config.passphraseEnc)
            put("autoBackup", config.autoBackup)
            put("lastBackupAt", config.lastBackupAt)
            put("lastBackupFingerprints", org.json.JSONArray(config.lastBackupFingerprints))
            put("lastBackupError", config.lastBackupError)
            put("lastRestoreAt", config.lastRestoreAt)
        }.toString()
    }
}

/**
 * Device-local per-project cloud backup configuration store. Pure JVM; the JSON string is persisted
 * by the caller (AppPreferences.projectCloudBackupsJson).
 */
object ProjectCloudBackupStore {

    fun encode(map: Map<String, ProjectCloudBackup>): String {
        val o = JSONObject()
        map.forEach { (id, config) -> o.put(id, ProjectCloudBackup.toJson(config)) }
        return o.toString()
    }

    fun decode(json: String): Map<String, ProjectCloudBackup> = runCatching {
        val o = JSONObject(json)
        val out = mutableMapOf<String, ProjectCloudBackup>()
        for (key in o.keys()) {
            out[key] = ProjectCloudBackup.fromJson(o.optString(key, "{}"))
        }
        out
    }.getOrDefault(emptyMap())

    fun get(json: String, projectId: String): ProjectCloudBackup =
        decode(json)[projectId] ?: ProjectCloudBackup()

    fun put(json: String, projectId: String, config: ProjectCloudBackup): String {
        val map = decode(json).toMutableMap()
        if (config == ProjectCloudBackup()) map.remove(projectId) else map[projectId] = config
        return encode(map)
    }

    fun remove(json: String, projectId: String): String {
        val map = decode(json).toMutableMap()
        map.remove(projectId)
        return encode(map)
    }
}
