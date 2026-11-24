package com.mobileharness.code.plugins

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Safe archive extraction for plugin and skill bundles, ported and adapted from AndroidHarness 4.0
 * (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * Archive members are data only. Escaping, ambiguous, oversized and special entries are skipped
 * instead of failing the whole bundle. Extraction is bounded (file count, per-file size, total
 * unpacked size) to prevent archive bombs, and every entry name is validated so it can never
 * resolve outside [destination].
 */
object PluginArchive {
    const val MAX_DOWNLOAD = 64L * 1024 * 1024            // 64 MiB input cap
    const val MAX_UNPACKED = 128L * 1024 * 1024           // 128 MiB total output cap
    const val MAX_FILE = 32L * 1024 * 1024                // 32 MiB per extracted file
    const val MAX_FILES = 10_000
    private const val MAX_DEPTH = 256
    private const val MAX_ENTRIES = 50_000

    /** Folders that never carry bundle content and often hold most of a repository's files. */
    val SKIPPED = setOf(
        ".git", "node_modules", "__MACOSX", ".DS_Store", ".hg", ".svn",
        "__pycache__", ".venv", ".pytest_cache", ".gradle", ".idea",
    )

    data class Result(val skipped: List<String>)

    /**
     * Extracts a ZIP or `.tar.gz`/`.tgz` archive into [destination] (which must be empty). Returns
     * the list of skipped entries (informational). Throws on a non-archive input or a size/entry
     * violation.
     */
    fun extract(archive: File, destination: File): Result {
        require(archive.isFile) { "Archive is missing: ${archive.name}" }
        require(archive.length() <= MAX_DOWNLOAD) { "Archive exceeds the ${MAX_DOWNLOAD / 1024 / 1024} MiB limit." }
        require(destination.mkdirs() || destination.isDirectory)
        require(destination.listFiles().isNullOrEmpty()) { "Extraction directory must be empty." }

        val budget = Budget(destination)
        val bytes = ByteArray(4)
        archive.inputStream().buffered().use { input ->
            input.mark(4)
            val n = input.read(bytes)
            input.reset()
            if (n >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
                extractZip(archive, budget)
            } else if (n >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                archive.inputStream().buffered().use { stream ->
                    TarArchiveInputStream(GzipCompressorInputStream(stream)).use { tar ->
                        extractTar(tar, budget)
                    }
                }
            } else {
                destination.deleteRecursively()
                error("Choose a ZIP or .tar.gz/.tgz bundle.")
            }
        }
        return Result(budget.skipped.toList())
    }

    private fun extractZip(archive: File, budget: Budget) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    budget.write(entry.name, entry.size, zip)
                } else {
                    budget.directory(entry.name)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, budget: Budget) {
        var entry = tar.nextTarEntry
        while (entry != null) {
            when {
                entry.isDirectory -> budget.directory(entry.name)
                entry.isFile -> budget.write(entry.name, entry.size, tar)
                else -> budget.skip(entry.name, "special file")
            }
            entry = tar.nextTarEntry
        }
    }

    /** Copies a local directory tree into [destination] with the same guards. */
    fun copyFolder(source: File, destination: File): Result {
        require(source.isDirectory) { "Choose a bundle folder." }
        require(destination.mkdirs() || destination.isDirectory)
        val budget = Budget(destination)
        copyTree(source, "", budget)
        return Result(budget.skipped.toList())
    }

    private fun copyTree(dir: File, prefix: String, budget: Budget) {
        dir.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
            if (child.name in SKIPPED) return@forEach
            if (Files.isSymbolicLink(child.toPath())) {
                budget.skip("$prefix${child.name}", "symlink")
                return@forEach
            }
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                budget.directory(relative)
                copyTree(child, relative, budget)
            } else if (child.isFile) {
                child.inputStream().use { budget.write(relative, child.length(), it) }
            }
        }
    }

    private class Budget(private val root: File) {
        var total = 0L
        var entries = 0
        var kept = 0
        val skipped = mutableListOf<String>()
        private val seen = mutableSetOf<String>()

        private fun clean(name: String): String? {
            val cleaned = name.replace('\\', '/').removePrefix("./").removeSuffix("/")
            if (cleaned.isBlank() || '\u0000' in cleaned) return null
            if (cleaned.startsWith("/")) return null
            val segments = cleaned.split('/')
            if (segments.any { it == ".." || it == "." || it.isEmpty() }) return null
            if (segments.any { it in SKIPPED }) return null
            if (segments.size > MAX_DEPTH) return null
            return cleaned
        }

        private fun target(name: String, directory: Boolean): File? {
            require(++entries <= MAX_ENTRIES) { "Bundle has too many entries." }
            val cleaned = clean(name) ?: return skip(name, "invalid path")
            if (cleaned.isEmpty() && directory) return root
            val file = File(root, cleaned)
            if (file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()).not()) {
                return skip(cleaned, "leaves the bundle")
            }
            if (!seen.add(cleaned.lowercase())) {
                return if (directory) file.takeIf { it.isDirectory } else skip(cleaned, "duplicate")
            }
            if (directory) {
                if (!file.mkdirs() && !file.isDirectory) return skip(cleaned, "conflicts with a file")
                return file
            }
            if (file.exists()) return skip(cleaned, "conflicting entry")
            if (!(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)) return skip(cleaned, "cannot create parent")
            require(++kept <= MAX_FILES) { "Bundle has too many files." }
            return file
        }

        fun skip(name: String, reason: String): File? {
            if (skipped.size < 50) skipped += "$name ($reason)"
            return null
        }

        fun directory(name: String) { target(name, true) }

        fun write(name: String, size: Long, input: InputStream) {
            if (size > MAX_FILE) { skip(name, "larger than ${MAX_FILE / 1024 / 1024} MiB"); return }
            val file = target(name, false) ?: return
            val limit = minOf(MAX_FILE, MAX_UNPACKED - total)
            try {
                FileOutputStream(file).use { out ->
                    total += copyLimited(input, out, limit)
                }
            } catch (e: IllegalArgumentException) {
                file.delete()
                if (limit == MAX_FILE) {
                    skip(name, "larger than ${MAX_FILE / 1024 / 1024} MiB")
                } else {
                    error("Bundle expands beyond the ${MAX_UNPACKED / 1024 / 1024} MiB limit.")
                }
            }
        }

        private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
            val buffer = ByteArray(8192)
            var size = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return size
                size += count
                require(size <= limit) { "Bundle entry exceeds its size limit." }
                output.write(buffer, 0, count)
            }
        }
    }
}
