package com.mobileharness.code.plugins

/**
 * Plugin system models. A plugin is an installable bundle (ZIP archive or a directory produced from
 * a GitHub/npm/marketplace source) with a `manifest.json` that declares what it is and what it may
 * do. Plugin *code* executes in the guest PRoot/Ubuntu runtime, never inside the Android app
 * process; this package only models/validates the manifest and registry, mirroring the fail-closed
 * [com.mobileharness.code.skills.SkillPolicy] approach.
 */

/** Where a plugin can be installed from. */
enum class PluginSource { GITHUB, NPM, ZIP, MARKETPLACE }

/** A permission a plugin requests; mapped onto the unified permission system at install time. */
enum class PluginPermission {
    /** Read files inside the workspace. */
    FILESYSTEM_READ,

    /** Write files inside the workspace. */
    FILESYSTEM_WRITE,

    /** Outbound network access. */
    NETWORK,

    /** Run a process (shell) inside the guest runtime. */
    EXEC,
}

/** Declared metadata for a plugin bundle. */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    /** Entry command/script inside the bundle, relative to its root. */
    val entry: String = "",
    /** Declared permissions; unknown permissions are dropped by [PluginPolicy]. */
    val permissions: List<PluginPermission> = emptyList(),
    /** Optional SHA-256 of the bundle, used for integrity verification. */
    val sha256: String? = null,
    /** Minimum schema version of the manifest format. */
    val manifestVersion: Int = 1,
)

/** An installed plugin's runtime state. */
data class Plugin(
    val manifest: PluginManifest,
    val source: PluginSource,
    /** Canonical source reference (repo URL, npm spec, zip URL, marketplace id). */
    val sourceRef: String,
    /** Where the bundle lives inside the guest runtime (relative to the project plugins dir). */
    val installPath: String,
    val enabled: Boolean = true,
    val installedAtMillis: Long = 0L,
)

/** A normalized download request produced by [PluginSourceResolver]. */
data class PluginSourceSpec(
    val source: PluginSource,
    /** HTTPS URL or npm package spec to fetch. */
    val ref: String,
    /** True when [ref] must be treated as an npm package name rather than a URL. */
    val isNpmPackage: Boolean = false,
)
