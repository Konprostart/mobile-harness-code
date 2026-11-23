package com.mobileharness.code.runtime

import org.json.JSONObject

/** High-level grouping shown in the Template Library. */
enum class TemplateCategory(val id: String) {
    WALLET("wallet"),
    DASHBOARD("dashboard"),
    ECOMMERCE("ecommerce"),
    CHAT("chat"),
    MEDIA("media"),
    PORTFOLIO("portfolio"),
    PRODUCTIVITY("productivity"),
    TOOLS("tools"),
    FILES("files"),
    LANDING("landing");

    companion object {
        fun fromId(id: String): TemplateCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One entry in the Appbuilder's built-in Template Library. An entry either points at a bundled
 * ZIP shipped inside the APK ([bundledAsset], fully offline, no network and no GitHub account) or
 * at a remote archive ([downloadUrl]) that is fetched, SHA-256 verified and then imported through
 * the exact same WEB App pipeline as a manually imported ZIP.
 *
 * The catalog is data (JSON) rather than code so new templates can be added without an app update.
 */
data class WebTemplate(
    val id: String,
    val name: String,
    val category: TemplateCategory,
    val description: String,
    /** SPDX-style short license id, e.g. "MIT" or "Apache-2.0". */
    val license: String,
    /** Public URL of the license text (canonical LICENSE file). */
    val licenseUrl: String,
    /** Canonical upstream repository URL. */
    val sourceUrl: String,
    /** Remote archive URL (codeload/zip); null for bundled templates. */
    val downloadUrl: String? = null,
    /** Asset path (relative to `assets/`) of the bundled ZIP; null for remote templates. */
    val bundledAsset: String? = null,
    /** Expected SHA-256 of the downloaded archive (required for remote templates). */
    val sha256: String? = null,
    /** True when the upstream project still needs an npm/node build before it is static HTML. */
    val buildRequired: Boolean = false,
    /** True when the upstream project requires a server/backend and cannot run offline in WebView. */
    val backendRequired: Boolean = false,
    /** Entry HTML file relative to the extracted asset root (default `index.html`). */
    val entryPoint: String = "index.html",
    /** Short emoji or glyph shown as the template's icon in the list. */
    val icon: String = "\uD83D\uDCC4",
    val tags: List<String> = emptyList(),
) {
    val isBundled: Boolean get() = bundledAsset != null
    /** True when the archive is fetched from a pinned remote URL (implies a SHA-256 digest). */
    val isRemote: Boolean get() = downloadUrl != null
    /** True when the template can be turned into a local WebView APK without a build or backend. */
    val isReady: Boolean get() = !buildRequired && !backendRequired &&
        (bundledAsset != null || (downloadUrl != null && sha256 != null))

    companion object {
        fun fromJson(json: JSONObject): WebTemplate? {
            val category = TemplateCategory.fromId(json.optString("category", "")) ?: return null
            val tags = mutableListOf<String>()
            json.optJSONArray("tags")?.let { array ->
                for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(tags::add)
            }
            return WebTemplate(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                category = category,
                description = json.optString("description", ""),
                license = json.optString("license", ""),
                licenseUrl = json.optString("licenseUrl", ""),
                sourceUrl = json.optString("sourceUrl", ""),
                downloadUrl = json.optString("downloadUrl", "").takeIf(String::isNotBlank),
                bundledAsset = json.optString("bundledAsset", "").takeIf(String::isNotBlank),
                sha256 = json.optString("sha256", "").takeIf(String::isNotBlank),
                buildRequired = json.optBoolean("buildRequired", false),
                backendRequired = json.optBoolean("backendRequired", false),
                entryPoint = json.optString("entryPoint", "index.html").takeIf(String::isNotBlank) ?: "index.html",
                icon = json.optString("icon", "\uD83D\uDCC4").takeIf(String::isNotBlank) ?: "\uD83D\uDCC4",
                tags = tags,
            )
        }
    }
}

/**
 * Fail-closed validation for a catalog entry. Mirrors [WebAppManifestPolicy]: an entry with an
 * unsafe id, an off-allowlist license, a non-HTTPS download/source URL, a bad SHA-256 or a
 * path-traversal entry point is rejected instead of being half-shown in the library.
 */
object WebTemplatePolicy {
    const val MAX_ID_CHARS = 120
    const val MAX_NAME_CHARS = 120
    const val MAX_DESCRIPTION_CHARS = 2_000
    const val MAX_ENTRY_CHARS = 512

    private val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /** Licenses acceptable for a template shipped/recommended by the Appbuilder. */
    val ALLOWED_LICENSES = setOf("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause")

    fun isValid(template: WebTemplate): Boolean {
        if (!ID_REGEX.matches(template.id)) return false
        if (template.name.isBlank() || template.name.length > MAX_NAME_CHARS) return false
        if (containsControl(template.name)) return false
        if (template.description.length > MAX_DESCRIPTION_CHARS) return false
        if (containsControl(template.description)) return false
        if (template.license !in ALLOWED_LICENSES) return false
        if (!isHttpsUrl(template.licenseUrl)) return false
        if (!isHttpsUrl(template.sourceUrl)) return false
        if (template.entryPoint.isNotBlank() && !WebAppManifestPolicy.isValidEntryPoint(template.entryPoint)) return false
        if (template.icon.length > 8 || containsControl(template.icon)) return false
        if (template.tags.any { it.length > 40 || containsControl(it) }) return false

        return when {
            template.isBundled -> {
                // Bundled asset path must be a safe relative path ending in .zip.
                val asset = template.bundledAsset.orEmpty()
                asset.isNotBlank() && !asset.startsWith('/') && !asset.contains('\\') &&
                    !asset.split('/').any { it == ".." } && asset.endsWith(".zip", ignoreCase = true)
            }
            template.isRemote -> {
                isHttpsUrl(template.downloadUrl.orEmpty()) &&
                    template.sha256 != null && SHA256_REGEX.matches(template.sha256)
            }
            // Informational entries (build-required or backend-required) are valid with only a
            // source/license URL so they can be listed with an explicit "needs build/server" badge,
            // but they can never be downloaded or turned into an APK (isReady is false).
            else -> template.buildRequired || template.backendRequired
        }
    }

    private fun isHttpsUrl(value: String): Boolean =
        value.length <= 2_048 && value.startsWith("https://") && !containsControl(value)

    private fun containsControl(value: String): Boolean = value.any { it.code < 0x20 }
}
