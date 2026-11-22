package com.mobileharness.code.runtime

import org.json.JSONObject

/**
 * Declared metadata for a Web App ZIP template. A WEB project wraps a ready HTML/CSS/JS application
 * inside a minimal Android WebView shell; the optional `manifest.json` at the ZIP root lets the
 * author declare a display name, an entry point and a small set of optional capabilities. The
 * manifest is validated fail-closed by [WebAppManifestPolicy] so an unknown or malformed template is
 * rejected rather than partially accepted.
 */
data class WebAppManifest(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val description: String = "",
    /** Entry HTML file relative to the asset root (defaults to `index.html`). */
    val entryPoint: String = "index.html",
    /** Optional icon file path relative to the asset root. Reserved for future launcher-icon use. */
    val icon: String? = null,
    /** Optional `#RRGGBB` theme color for the status/navigation bar and WebView background. */
    val theme: String? = null,
    /** Declared capabilities; unknown entries are dropped by [WebAppManifestPolicy]. */
    val permissions: List<String> = emptyList(),
    /** Minimum Appbuilder version that understands this manifest schema. */
    val minimumAppBuilderVersion: String? = null,
    /** Optional SHA-256 of the web asset bundle, used for integrity verification. */
    val sha256: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): WebAppManifest {
            val permissions = mutableListOf<String>()
            json.optJSONArray("permissions")?.let { array ->
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) permissions += value
                }
            }
            return WebAppManifest(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                version = json.optString("version", "1.0"),
                description = json.optString("description", ""),
                entryPoint = json.optString("entryPoint", "index.html"),
                icon = json.optString("icon", "").takeIf(String::isNotBlank),
                theme = json.optString("theme", "").takeIf(String::isNotBlank),
                permissions = permissions,
                minimumAppBuilderVersion = json.optString("minimumAppBuilderVersion", "")
                    .takeIf(String::isNotBlank),
                sha256 = json.optString("sha256", "").takeIf(String::isNotBlank),
            )
        }
    }
}

/**
 * Pure, fail-closed validation for Web App manifests. Mirrors the [com.mobileharness.code.plugins.PluginPolicy]
 * approach: an invalid id, oversized metadata, a path-traversal entry point or an unknown permission
 * is rejected instead of silently accepted. Only a small, strictly defined set of capabilities is
 * recognized; everything else is dropped so a hostile template cannot request arbitrary access.
 */
object WebAppManifestPolicy {
    const val MAX_ID_CHARS = 120
    const val MAX_NAME_CHARS = 120
    const val MAX_DESCRIPTION_CHARS = 2_000
    const val MAX_ENTRY_CHARS = 512
    const val MAX_VERSION_CHARS = 64

    /** Id pattern: letters, digits, `.`, `-`, `_` — no slashes or path traversal. */
    private val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
    private val HEX_COLOR_REGEX = Regex("^#[0-9a-fA-F]{6}$")

    /** The only capabilities a Web App template may request. Anything else is dropped. */
    val ALLOWED_PERMISSIONS = setOf(
        "network",   // grants the INTERNET permission to the generated shell
        "camera",    // reserved: grants CAMERA permission + getUserMedia handling
        "vibrate",   // enables the AndroidBridge.vibrate function
        "clipboard", // enables the AndroidBridge clipboard functions
        "share",     // enables the AndroidBridge.share function
    )

    fun isValidManifest(manifest: WebAppManifest): Boolean {
        if (!ID_REGEX.matches(manifest.id)) return false
        if (manifest.name.isBlank() || manifest.name.length > MAX_NAME_CHARS) return false
        if (containsControl(manifest.name)) return false
        if (!isValidVersion(manifest.version)) return false
        if (manifest.description.length > MAX_DESCRIPTION_CHARS) return false
        if (containsControl(manifest.description)) return false
        if (!isValidEntryPoint(manifest.entryPoint)) return false
        if (manifest.icon != null && !isValidEntryPoint(manifest.icon)) return false
        if (manifest.theme != null && !HEX_COLOR_REGEX.matches(manifest.theme)) return false
        if (manifest.minimumAppBuilderVersion != null && !isValidVersion(manifest.minimumAppBuilderVersion)) return false
        if (manifest.sha256 != null && !Regex("^[0-9a-fA-F]{64}$").matches(manifest.sha256)) return false
        return true
    }

    /** Drops unknown permissions and returns a normalized manifest. */
    fun normalize(manifest: WebAppManifest): WebAppManifest =
        manifest.copy(permissions = manifest.permissions.filter { it in ALLOWED_PERMISSIONS }.distinct())

    /** True when the entry point is a safe relative path to an HTML file. */
    fun isValidEntryPoint(entryPoint: String): Boolean =
        entryPoint.isNotBlank() &&
            entryPoint.length <= MAX_ENTRY_CHARS &&
            !containsControl(entryPoint) &&
            !entryPoint.startsWith('/') &&
            !entryPoint.contains('\\') &&
            !entryPoint.split('/').any { it == ".." } &&
            entryPoint.endsWith(".html", ignoreCase = true)

    fun isValidVersion(version: String): Boolean =
        version.isNotBlank() && version.length <= MAX_VERSION_CHARS && !version.any { it.code < 0x20 || it == '/' }

    /** Parses and validates a manifest JSON object; returns null when absent or invalid. */
    fun parse(json: JSONObject): WebAppManifest? {
        val manifest = WebAppManifest.fromJson(json)
        if (!isValidManifest(manifest)) return null
        return normalize(manifest)
    }

    private fun containsControl(value: String): Boolean = value.any { it.code < 0x20 }
}
