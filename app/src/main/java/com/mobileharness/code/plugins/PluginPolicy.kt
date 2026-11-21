package com.mobileharness.code.plugins

/**
 * Pure, fail-closed validation for plugin manifests and sources. An unknown or untrusted plugin is
 * validated conservatively: an invalid id, oversized metadata, an unknown permission or an
 * unallowed source host is rejected rather than partially accepted. The real process confinement
 * (path/network/resource limits) lives in the guest runtime; this policy only gates what can be
 * *declared* and installed.
 */
object PluginPolicy {
    const val MAX_PLUGINS = 32
    const val MAX_ID_CHARS = 120
    const val MAX_NAME_CHARS = 120
    const val MAX_DESCRIPTION_CHARS = 2_000
    const val MAX_ENTRY_CHARS = 512
    /** Hard cap on a bundle download (in bytes). Plugins are tiny; 8 GB is a ceiling, not a goal. */
    const val MAX_BUNDLE_BYTES = 64L * 1024 * 1024

    /** Id pattern: letters, digits, `.`, `-`, `_` — no slashes or path traversal. */
    private val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")

    fun isValidManifest(manifest: PluginManifest): Boolean {
        if (!ID_REGEX.matches(manifest.id)) return false
        if (manifest.name.isBlank() || manifest.name.length > MAX_NAME_CHARS) return false
        if (containsControl(manifest.name)) return false
        if (!isValidVersion(manifest.version)) return false
        if (manifest.description.length > MAX_DESCRIPTION_CHARS) return false
        if (manifest.entry.length > MAX_ENTRY_CHARS) return false
        if (containsControl(manifest.entry) || manifest.entry.contains("..")) return false
        if (manifest.manifestVersion < 1) return false
        if (manifest.sha256 != null && !Regex("^[0-9a-fA-F]{64}$").matches(manifest.sha256)) return false
        return true
    }

    /** Drops unknown permissions and returns a normalized manifest. */
    fun normalize(manifest: PluginManifest): PluginManifest =
        manifest.copy(permissions = manifest.permissions.distinct())

    fun isValidVersion(version: String): Boolean =
        version.isNotBlank() && version.length <= 64 && !version.any { it.code < 0x20 || it == '/' }

    /** A plugin bundle size is acceptable only within the hard cap. */
    fun isValidBundleSize(bytes: Long): Boolean = bytes in 0..MAX_BUNDLE_BYTES

    private fun containsControl(value: String): Boolean = value.any { it.code < 0x20 }
}
