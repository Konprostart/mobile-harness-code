package com.mobileharness.code.runtime

import org.json.JSONObject
import java.io.File

/** How a Web App ZIP template should be treated by the Appbuilder. */
enum class WebAppKind {
    /** Pure HTML/CSS/JS with an `index.html` ready to copy as-is (no build step). */
    STATIC,

    /** An already-built SPA whose `dist/` or `build/` directory contains `index.html`. */
    BUILT_SPA,

    /** A `package.json` project with no built `index.html` — requires an explicit npm build. */
    NPM_REQUIRED,

    /** Backend markers present (e.g. PHP/Express/Django) — cannot run as a static WebView app. */
    BACKEND_REQUIRED,

    /** No usable entry point was found. */
    INVALID,
}

/** The result of scanning an extracted Web App ZIP. */
data class WebAppScanResult(
    val kind: WebAppKind,
    /** The directory whose contents are copied into the WebView shell assets. */
    val assetRoot: File,
    /** Entry HTML file relative to [assetRoot]. */
    val entryPoint: String,
    /** A validated custom `manifest.json` when present, or null. */
    val manifest: WebAppManifest?,
    /** Whether a PWA service worker was detected (informational, does not block import). */
    val hasServiceWorker: Boolean,
    /** Human-readable problems, empty for a healthy template. */
    val issues: List<String> = emptyList(),
) {
    val isReady: Boolean get() = kind == WebAppKind.STATIC || kind == WebAppKind.BUILT_SPA
}

/**
 * Pure scanner that inspects an extracted ZIP directory and decides where the WebView shell should
 * take its `index.html` from. It is intentionally conservative: it never triggers a build and never
 * touches the network; a project that still needs `npm run build` is reported as [WebAppKind.NPM_REQUIRED]
 * rather than silently half-imported.
 */
object WebAppZipScanner {
    private val BACKEND_FILE_NAMES = setOf(
        "composer.json", "requirements.txt", "pyproject.toml", "manage.py", "Pipfile",
    )

    fun scan(root: File): WebAppScanResult {
        val issues = mutableListOf<String>()
        val files = root.walkTopDown()
            .onEnter { dir -> dir.name != ".git" && dir.name != "node_modules" }
            .filter { it.isFile }
            .toList()
        val relative = files.map { it.relativeTo(root).invariantSeparatorsPath }

        if (files.isEmpty()) return WebAppScanResult(WebAppKind.INVALID, root, "index.html", null, false, listOf("empty archive"))

        val manifest = readManifest(root)

        // 1. Custom manifest may declare a non-default entry point.
        val declaredEntry = manifest?.entryPoint

        // 2. Find a usable index.html and the directory it lives in.
        val indexCandidates = locateIndex(root, files, declaredEntry)

        val backendDetected = relative.any { name ->
            val last = name.substringAfterLast('/')
            last in BACKEND_FILE_NAMES
        }

        val hasServiceWorker = relative.any { name ->
            val last = name.substringAfterLast('/')
            last == "service-worker.js" || last == "sw.js" || last == "sw.ts"
        }

        return when {
            indexCandidates != null -> {
                val kind = if (indexCandidates.third) WebAppKind.BUILT_SPA else WebAppKind.STATIC
                WebAppScanResult(kind, indexCandidates.first, indexCandidates.second, manifest, hasServiceWorker, issues)
            }
            backendDetected -> WebAppScanResult(WebAppKind.BACKEND_REQUIRED, root, "index.html", manifest, hasServiceWorker, listOf("backend project"))
            relative.any { it.substringAfterLast('/') == "package.json" } ->
                WebAppScanResult(WebAppKind.NPM_REQUIRED, root, "index.html", manifest, hasServiceWorker, listOf("needs build"))
            else -> WebAppScanResult(WebAppKind.INVALID, root, "index.html", manifest, hasServiceWorker, listOf("no index.html"))
        }
    }

    /**
     * Locates the entry HTML file. Returns a triple of (assetRoot, entryPoint, isBuiltSpa).
     * Order of preference:
     *  1. a declared entry point that exists relative to the root;
     *  2. `index.html` at the root;
     *  3. `index.html` in a single nested directory (common for GitHub ZIP downloads);
     *  4. `index.html` under `dist/` or `build/` (an already-built SPA).
     */
    private fun locateIndex(root: File, files: List<File>, declaredEntry: String?): Triple<File, String, Boolean>? {
        if (declaredEntry != null && declaredEntry != "index.html") {
            val declared = File(root, declaredEntry)
            if (declared.isFile && declared.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                return Triple(root, declaredEntry, false)
            }
        }

        val indexFiles = files.filter { it.name.equals("index.html", ignoreCase = true) }
        if (indexFiles.isEmpty()) return null

        // index.html directly at the root
        val rootIndex = File(root, "index.html")
        if (rootIndex.isFile) return Triple(root, "index.html", false)

        // index.html under dist/ or build/
        val built = indexFiles.firstOrNull { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            rel.startsWith("dist/") || rel.startsWith("build/")
        }
        if (built != null) {
            val rel = built.relativeTo(root).invariantSeparatorsPath
            val segments = rel.split('/')
            val topDir = segments.first()
            // asset root is the top-level dist/ or build/ directory
            return Triple(File(root, topDir), segments.drop(1).joinToString("/"), true)
        }

        // index.html in a single nested directory
        val topLevelDirs = root.listFiles().orEmpty().filter { it.isDirectory }
        if (topLevelDirs.size == 1) {
            val single = topLevelDirs.single()
            val nested = File(single, "index.html")
            if (nested.isFile) return Triple(single, "index.html", false)
        }

        return null
    }

    private fun readManifest(root: File): WebAppManifest? {
        val manifestFile = File(root, "manifest.json")
        if (!manifestFile.isFile) return null
        return runCatching {
            val json = JSONObject(manifestFile.readText())
            WebAppManifestPolicy.parse(json)
        }.getOrNull()
    }
}
