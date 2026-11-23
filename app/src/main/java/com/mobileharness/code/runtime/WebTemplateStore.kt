package com.mobileharness.code.runtime

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Materializes a [WebTemplate] into a directory of extracted web assets. The result is then handed
 * to [WebAppZipScanner] and [AndroidProjectTemplateGenerator] exactly like a manually imported ZIP,
 * so a library template and a hand-built ZIP share one code path.
 *
 * Bundled templates are read from APK assets (fully offline, no network, no GitHub account).
 * Remote templates are downloaded over HTTPS, SHA-256 verified against the pinned catalog digest
 * and then extracted with the same path-traversal / size / entry-count guards as ZIP import.
 */
class WebTemplateStore(private val context: Context) {
    /** Loads the bundled catalog; malformed entries are dropped by [WebTemplateCatalog]. */
    fun loadCatalog(): List<WebTemplate> = runCatching {
        val raw = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        WebTemplateCatalog.fromJson(raw)
    }.getOrDefault(emptyList())

    /**
     * Extracts the template's archive into [destination] (created if needed). Returns the
     * extraction directory; callers then run [WebAppZipScanner.scan] to locate the entry point.
     */
    fun materialize(template: WebTemplate, destination: File): File {
        destination.mkdirs()
        require(destination.isDirectory) { "Template staging directory unavailable" }
        val zip = when {
            template.isBundled -> downloadBundledZip(template)
            template.isRemote -> downloadRemoteZip(template)
            else -> error("Template is not downloadable")
        }
        return try {
            extractZip(zip, destination)
            destination
        } finally {
            zip.delete()
        }
    }

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
        require(isAllowedDownloadHost(url)) { "Template download host not allowed" }
        val temp = File.createTempFile("web-template-", ".zip", context.cacheDir)
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.connect()
            val status = connection.responseCode
            require(status in 200..299) { "Template download failed (HTTP $status)" }
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { output ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "Template archive too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        require(sha256Hex(temp).equals(expected, ignoreCase = true)) { "Template SHA-256 mismatch" }
        return temp
    }

    companion object {
        const val CATALOG_ASSET = "templates/catalog.json"
        const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L // decompressed cap for template ZIPs
        const val MAX_ARCHIVE_ENTRIES = 60_000
        const val MAX_ENTRY_PATH_CHARS = 4_096
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
