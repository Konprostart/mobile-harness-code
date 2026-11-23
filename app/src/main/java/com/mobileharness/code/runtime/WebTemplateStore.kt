package com.mobileharness.code.runtime

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

/** The result of materializing a template archive into an on-device directory. */
data class MaterializedTemplate(
    /** Directory holding the extracted web assets (the root passed to [WebAppZipScanner]). */
    val dir: File,
    /** True when [dir] is a short-lived staging directory the caller must delete. */
    val temporary: Boolean,
)

/**
 * Materializes a [WebTemplate] into a directory of extracted web assets. The result is then handed
 * to [WebAppZipScanner] and [AndroidProjectTemplateGenerator] exactly like a manually imported ZIP,
 * so a library template and a hand-built ZIP share one code path.
 *
 * Bundled templates are read from APK assets into a short-lived staging directory (fully offline,
 * no network, no GitHub account). Remote templates are downloaded over HTTPS, SHA-256 verified
 * against the pinned catalog digest and then extracted into a *persistent* cache directory under
 * `filesDir` (not the system cache), so a template the user has downloaded is reused without
 * another download and is never silently reclaimed by the OS.
 */
class WebTemplateStore(private val context: Context) {
    /** Loads the bundled catalog shipped in the APK; malformed entries are dropped. */
    fun loadCatalog(): List<WebTemplate> = runCatching {
        val raw = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        WebTemplateCatalog.fromJson(raw)
    }.getOrDefault(emptyList())

    /**
     * Fetches the remote catalog over HTTPS and returns it, or null when it is unreachable or
     * malformed. A successful fetch is cached on disk so the last known-good catalog survives an
     * app restart even if the host is later offline.
     */
    fun loadRemoteCatalog(): List<WebTemplate>? {
        val raw = runCatching { downloadText(REMOTE_CATALOG_URL, MAX_CATALOG_BYTES) }.getOrNull()
            ?: return null
        val catalog = runCatching { WebTemplateCatalog.fromJson(raw) }.getOrNull()
            ?: return null
        if (catalog.isNotEmpty()) {
            runCatching { cachedCatalogFile().writeText(raw) }
        }
        return catalog
    }

    /** Returns the last successfully fetched remote catalog, or null if none is cached yet. */
    fun loadCachedCatalog(): List<WebTemplate>? = runCatching {
        val file = cachedCatalogFile()
        if (!file.isFile) return null
        WebTemplateCatalog.fromJson(file.readText())
    }.getOrNull()

    /**
     * Materializes the template's archive. Remote archives are extracted into a persistent cache
     * directory and reused on later calls; bundled archives use a short-lived staging directory.
     */
    fun materialize(template: WebTemplate): MaterializedTemplate = when {
        template.isBundled -> {
            val staging = File(context.cacheDir, "template-import/${template.id}-${System.nanoTime()}")
            staging.mkdirs()
            try {
                extractZip(downloadBundledZip(template), staging)
                MaterializedTemplate(staging, temporary = true)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
        template.isRemote -> MaterializedTemplate(materializeRemoteCached(template), temporary = false)
        else -> error("Template is not downloadable")
    }

    /** True when the template's remote archive is already extracted into the persistent cache. */
    fun isCached(template: WebTemplate): Boolean = template.isRemote && isComplete(cachedDir(template))

    /** Deletes a cached remote template and returns the bytes reclaimed (0 when nothing was cached). */
    fun deleteCached(template: WebTemplate): Long {
        if (!template.isRemote) return 0L
        val dir = cachedDir(template)
        val reclaimed = if (dir.exists()) dir.walkTopDown().filter(File::isFile).sumOf { it.length() } else 0L
        dir.deleteRecursively()
        return reclaimed
    }

    /** Total on-disk size of all cached remote templates (bytes). */
    fun cachedBytes(): Long {
        val root = cacheRoot()
        if (!root.isDirectory) return 0L
        return root.walkTopDown().filter(File::isFile).sumOf { it.length() }
    }

    /** Returns a cached thumbnail file for [url], downloading (and caching) it on first use. */
    fun thumbnail(url: String): File? {
        if (!isAllowedDownloadHost(url)) return null
        val cached = thumbnailFile(url)
        if (cached.isFile) return cached
        return runCatching {
            downloadBinary(url, cached, MAX_THUMBNAIL_BYTES)
            if (cached.isFile) cached else null
        }.getOrNull()
    }

    /** Materializes a remote archive into the persistent cache, downloading and verifying if needed. */
    private fun materializeRemoteCached(template: WebTemplate): File {
        val dir = cachedDir(template)
        if (isComplete(dir)) return dir

        val staging = File(cacheRoot(), ".staging-${template.id}-${System.nanoTime()}")
        staging.deleteRecursively()
        val zip = downloadRemoteZip(template)
        try {
            extractZip(zip, staging)
            // Mark complete only after a fully successful extraction so an interrupted download
            // or extraction is never treated as a valid cache entry.
            File(staging, CACHE_COMPLETE_MARKER).writeText("ok")
            dir.deleteRecursively()
            val moved = if (staging.renameTo(dir)) true else staging.copyRecursively(dir, overwrite = true)
            require(moved) { "Template cache write failed" }
            require(isComplete(dir)) { "Template cache is incomplete" }
            return dir
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun cachedDir(template: WebTemplate): File = cachedDirFor(cacheRoot(), template)

    private fun isComplete(dir: File): Boolean = isCompleteDir(dir)

    private fun cacheRoot(): File = File(context.filesDir, "templates").apply { mkdirs() }

    private fun cachedCatalogFile(): File = File(cacheRoot(), ".catalog.json")

    private fun thumbnailFile(url: String): File =
        File(cacheRoot(), "thumbs/${url.hashCode().toUInt().toString(16)}.img").apply { parentFile?.mkdirs() }

    /** Streams a bundled asset ZIP to a temporary file. */
    private fun downloadBundledZip(template: WebTemplate): File {
        val assetPath = requireNotNull(template.bundledAsset)
        val temp = File.createTempFile("web-template-", ".zip", context.cacheDir)
        context.assets.open(assetPath).buffered().use { input ->
            temp.outputStream().buffered().use { input.copyTo(it) }
        }
        return temp
    }

    /** Downloads a remote archive and verifies its SHA-256 against the pinned catalog digest. */
    private fun downloadRemoteZip(template: WebTemplate): File {
        val url = requireNotNull(template.downloadUrl)
        val expected = requireNotNull(template.sha256)
        val temp = File.createTempFile("web-template-", ".zip", context.cacheDir)
        try {
            downloadBinary(url, temp, MAX_ARCHIVE_BYTES)
            require(sha256Hex(temp).equals(expected, ignoreCase = true)) { "Template SHA-256 mismatch" }
            return temp
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    /** Reads a small HTTPS resource into memory (used for the remote catalog JSON). */
    private fun downloadText(url: String, maxBytes: Long): String {
        val temp = File.createTempFile("web-catalog-", ".json", context.cacheDir)
        try {
            downloadBinary(url, temp, maxBytes)
            return temp.readText()
        } finally {
            temp.delete()
        }
    }

    /** Downloads an HTTPS resource to [dest], enforcing host allow-list and a size cap. */
    private fun downloadBinary(url: String, dest: File, maxBytes: Long) {
        require(isAllowedDownloadHost(url)) { "Download host not allowed" }
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.connect()
            val status = connection.responseCode
            require(status in 200..299) { "Download failed (HTTP $status)" }
            connection.inputStream.buffered().use { input ->
                dest.outputStream().buffered().use { output ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "Download too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CATALOG_ASSET = "templates/catalog.json"

        /**
         * Canonical remote catalog URL. This is the *single* point of configuration for the
         * hosting location; the app does not depend on the template author's account or services.
         * To move hosting, change this one constant (and the corresponding asset URLs).
         */
        const val REMOTE_CATALOG_URL =
            "https://raw.githubusercontent.com/Konprostart/mobile-harness-templates/main/catalog.json"

        const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L // decompressed cap for template ZIPs
        const val MAX_ARCHIVE_ENTRIES = 60_000
        const val MAX_ENTRY_PATH_CHARS = 4_096
        const val MAX_CATALOG_BYTES = 4L * 1024L * 1024L
        const val MAX_THUMBNAIL_BYTES = 8L * 1024L * 1024L
        const val CACHE_COMPLETE_MARKER = ".pocketdev-complete"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val USER_AGENT = "Mobile-Harness-Code-Template-Library/1.0"

        /**
         * Extracts a ZIP archive into [destination] with path-traversal, size and entry-count
         * guards. Purely file-based so it is unit-testable without an Android device.
         */
        fun extractZip(zip: File, destination: File) {
            require(zip.isFile) { "Template archive missing" }
            val destinationPath = destination.canonicalFile.toPath()
            var extractedBytes = 0L
            var entries = 0
            zip.inputStream().buffered().use { raw ->
                ZipInputStream(raw).use { stream ->
                    while (true) {
                        val entry = stream.nextEntry ?: break
                        entries++
                        require(entries <= MAX_ARCHIVE_ENTRIES) { "Template ZIP has too many files" }
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        require(entryName.length <= MAX_ENTRY_PATH_CHARS) { "Template entry path too long" }
                        require(entryName.isNotBlank() && '\u0000' !in entryName) { "Invalid template entry path" }
                        if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store") || entryName == ".DS_Store") {
                            stream.closeEntry()
                            continue
                        }
                        val target = File(destination, entryName).canonicalFile
                        require(target.toPath().startsWith(destinationPath)) { "Unsafe template entry path" }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    require(extractedBytes <= MAX_ARCHIVE_BYTES) { "Template ZIP too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        stream.closeEntry()
                    }
                }
            }
        }

        /** Stable, content-addressed cache directory for a remote template under [cacheRoot]. */
        fun cachedDirFor(cacheRoot: File, template: WebTemplate): File {
            val key = (template.sha256 ?: template.id).take(12)
            return File(cacheRoot, "template-${template.id}-${key}")
        }

        /** True when [dir] carries the completeness marker written only after a full extraction. */
        fun isCompleteDir(dir: File): Boolean = File(dir, CACHE_COMPLETE_MARKER).isFile

        /** HTTPS + host allow-list for remote downloads; blocks SSRF and downgrade attacks. */
        fun isAllowedDownloadHost(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (uri.scheme?.lowercase() != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return host == "github.com" ||
                host.endsWith(".github.com") ||
                host.endsWith(".githubusercontent.com")
        }
    }
}
