package com.mobileharness.code.sidekick

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** What kind of source the assistant is allowed to read during background research. */
enum class SidekickSourceKind { DIRECTORY, URL }

data class SidekickSource(val kind: SidekickSourceKind, val value: String)

/**
 * Pure, fail-closed planning and local-context collection for the Sidekick assistant's
 * background research mode. Web sources are restricted to `https://` so the assistant can
 * never fetch `file://`, `http://` (cleartext) or localhost/loopback endpoints, and local
 * sources are capped so research stays bounded and never blocks the coding agent.
 */
object SidekickResearch {
    /** Maximum number of entries parsed from the user's configured source list. */
    const val MAX_SOURCES = 24
    const val MAX_FILES = 200
    const val MAX_TOTAL_BYTES = 1_000_000L
    const val MAX_FILE_BYTES = 64_000L
    const val MAX_URL_BYTES = 200_000L
    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "apk", "zip", "tar", "gz", "zst",
        "jar", "dex", "so", "o", "a", "class", "wasm", "mp3", "mp4", "wav", "pdf", "ttf", "otf",
    )

    /** Splits the user's raw sources string into typed, deduplicated, validated sources. */
    fun parseSources(raw: String): List<SidekickSource> = raw
        .lineSequence()
        .flatMap { it.split(',') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_SOURCES)
        .mapNotNull { value ->
            when {
                value.startsWith("https://") -> SidekickSource(SidekickSourceKind.URL, value)
                value.startsWith("http://") || value.startsWith("file://") ||
                    value.startsWith("content://") || value.startsWith("javascript:") -> null
                else -> SidekickSource(SidekickSourceKind.DIRECTORY, value)
            }
        }.toList()

    /** Whether a URL is safe to fetch from the assistant (https only, no loopback/private hosts). */
    fun isAllowedUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isBlank() || host == "localhost") return false
        val hostOnly = host.removeSuffix(".")
        if (hostOnly == "localhost") return false
        val address = runCatching { java.net.InetAddress.getByName(hostOnly) }.getOrNull()
        return address == null || !(address.isLoopbackAddress || address.isSiteLocalAddress)
    }

    /**
     * Reads the user's allowed local directories into a bounded text context. Symlinks and
     * binary files are skipped; nothing is written and no network is touched.
     */
    fun collectDirectoryContext(root: File, maxFiles: Int = MAX_FILES, maxTotalBytes: Long = MAX_TOTAL_BYTES): String {
        if (!root.isDirectory) return ""
        val builder = StringBuilder()
        var files = 0
        var totalBytes = 0L
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty() && files < maxFiles && totalBytes < maxTotalBytes) {
            val dir = pending.removeFirst()
            val children = dir.listFiles() ?: continue
            children.sortedBy { it.name }.forEach { file ->
                if (files >= maxFiles || totalBytes >= maxTotalBytes) return@forEach
                when {
                    file.isDirectory -> if (pending.size < 4096) pending.addLast(file)
                    file.isFile && !isBinary(file) -> {
                        val text = readCapped(file, maxTotalBytes - totalBytes)
                        if (text.isNotBlank()) {
                            builder.append("--- ").append(file.path).append(" ---\n")
                            builder.append(text).append('\n')
                            totalBytes += text.length.toLong().coerceAtMost(MAX_FILE_BYTES)
                            files++
                        }
                    }
                }
            }
        }
        return builder.toString().trim()
    }

    /** Best-effort fetch of an https source, capped so a slow/huge page cannot stall research. */
    fun fetchUrl(value: String): String = runCatching {
        if (!isAllowedUrl(value)) return@runCatching ""
        val connection = (URL(value).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "text/plain,text/html,application/json")
            setRequestProperty("User-Agent", "MobileHarnessCode-Sidekick/1.0")
        }
        val code = connection.responseCode
        if (code !in 200..299) return@runCatching ""
        val body = connection.inputStream.bufferedReader().use { reader ->
            buildString {
                val buffer = CharArray(8 * 1024)
                while (length < MAX_URL_BYTES) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_URL_BYTES.toInt() - length))
                    if (count <= 0) break
                    append(buffer, 0, count)
                }
            }
        }
        connection.disconnect()
        stripMarkup(body).trim().take(MAX_URL_BYTES.toInt())
    }.getOrDefault("")

    /** Aggregates a typed source list into a single bounded context string for the model. */
    fun gatherContext(
        sources: List<SidekickSource>,
        maxFiles: Int = MAX_FILES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): String {
        val parts = mutableListOf<String>()
        for (source in sources) {
            when (source.kind) {
                SidekickSourceKind.DIRECTORY ->
                    collectDirectoryContext(File(source.value), maxFiles, maxTotalBytes)
                        .takeIf(String::isNotBlank)?.let(parts::add)
                SidekickSourceKind.URL -> fetchUrl(source.value).takeIf(String::isNotBlank)?.let(parts::add)
            }
        }
        return parts.joinToString("\n\n").take(maxTotalBytes.toInt() * 3)
    }

    private fun isBinary(file: File): Boolean =
        file.extension.lowercase() in BINARY_EXTENSIONS

    private fun readCapped(file: File, remaining: Long): String {
        if (remaining <= 0 || file.length() > MAX_FILE_BYTES) return ""
        return runCatching {
            val text = file.readText()
            text.take(MAX_FILE_BYTES.toInt())
        }.getOrDefault("")
    }

    private fun stripMarkup(html: String): String = html
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("&#39;"), "'")
        .replace(Regex("\\s+"), " ")
}
