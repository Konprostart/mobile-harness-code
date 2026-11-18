package com.mobileharness.code.update

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Pure, side-effect-free parsing of the update manifest.
 *
 * Returns null when no newer build exists for the requested variant, when the
 * version code does not strictly exceed [currentVersionCode], or when the
 * artifact URL is not a secure HTTPS URL. Keeping this logic free of Android
 * and network dependencies makes the version gate directly unit-testable.
 */
internal fun parseAppUpdateManifest(
    json: String,
    currentVersionCode: Long,
    variant: String,
): AppUpdateInfo? {
    val root = JSONObject(json)
    val versionCode = root.optLong("versionCode")
    if (versionCode <= currentVersionCode) return null
    val artifact = root.optJSONObject("artifacts")?.optJSONObject(variant)
        ?: root.optJSONObject(variant)
        ?: root
    val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
    if (!url.startsWith("https://")) return null
    return AppUpdateInfo(
        versionCode = versionCode,
        versionName = root.optString("versionName", versionCode.toString()),
        apkUrl = url,
        sha256 = artifact.optString("sha256").lowercase(),
        sizeBytes = artifact.optLong("sizeBytes", -1L),
        notes = root.optString("notes"),
    )
}

/** Pure SHA-256 helpers, extracted so checksum verification can be unit-tested. */
internal object UpdateChecksum {
    fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun of(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
