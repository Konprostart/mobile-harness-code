package com.mobileharness.code.tools

import java.io.File

/**
 * Pure, bounded local filesystem tools for the Sidekick assistant: a project-structure listing
 * ("repo map"), a bounded recursive text search, and a safe bounded file read. Every operation is
 * confined to the workspace root by [WorkspacePathGuard] and capped by explicit limits so a huge
 * or pathological tree can never exhaust memory or flood the model context.
 *
 * No network, no writes, no shell: these tools only read app-private workspace files.
 */
object WorkspaceTools {
    /** Text extensions considered searchable/readable; anything else is treated as binary. */
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
        "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
        "gradle", "properties", "toml", "ini", "conf", "sql", "gitignore", "gitattributes",
    )

    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "apk", "zip", "tar", "gz", "zst",
        "jar", "dex", "so", "o", "a", "class", "wasm", "mp3", "mp4", "wav", "pdf", "ttf", "otf",
    )

    /** Limits used when the caller does not specify tighter (Eco Mode) bounds. */
    const val DEFAULT_MAX_DEPTH = 5
    const val DEFAULT_MAX_ENTRIES = 400
    const val DEFAULT_MAX_SEARCH_RESULTS = 40
    const val DEFAULT_MAX_FILE_BYTES = 64_000L
    const val DEFAULT_MAX_TOTAL_BYTES = 200_000L
    const val DEFAULT_READ_BYTES = 64_000

    data class SearchHit(val path: String, val line: Int, val snippet: String)

    /**
     * Renders a bounded, indented tree of the workspace. Directories are recursed up to [maxDepth]
     * (0 = root only), entries are truncated at [maxEntries], and symlinks are skipped.
     */
    fun listStructure(
        root: File,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): String {
        if (!root.isDirectory) return "(workspace root not found)"
        val sb = StringBuilder()
        sb.append(root.name).append('/').append('\n')
        var entries = 0
        fun walk(dir: File, depth: Int, prefix: String) {
            if (depth > maxDepth || entries >= maxEntries) return
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for ((index, child) in children.withIndex()) {
                if (entries >= maxEntries) {
                    if (index == 0) sb.append(prefix).append("… (truncated)\n")
                    break
                }
                val last = index == children.lastIndex
                val connector = if (last) "└── " else "├── "
                val isDir = child.isDirectory
                val symlink = runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)
                if (symlink) continue
                sb.append(prefix).append(connector).append(child.name).append(if (isDir) "/" else "").append('\n')
                entries++
                if (isDir) walk(child, depth + 1, prefix + if (last) "    " else "│   ")
            }
        }
        walk(root, 1, "")
        if (entries >= maxEntries) sb.append("… (truncated)\n")
        return sb.toString()
    }

    /**
     * Recursively searches workspace text files for [query] (case-insensitive substring) and by
     * filename. Returns ranked matches as `path:line: snippet`. Bounded by [maxResults],
     * [maxFileBytes] per file and [maxTotalBytes] overall; binary and oversized files are skipped.
     */
    fun searchFiles(
        root: File,
        query: String,
        maxResults: Int = DEFAULT_MAX_SEARCH_RESULTS,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    ): String {
        if (!root.isDirectory) return "(workspace root not found)"
        val needle = query.trim()
        if (needle.isEmpty()) return "(empty search query)"
        if (needle.length > 200) return "(search query too long)"

        val hits = mutableListOf<SearchHit>()
        var totalBytes = 0L
        val needleLower = needle.lowercase()
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty() && hits.size < maxResults && totalBytes < maxTotalBytes) {
            val dir = pending.removeFirst()
            val children = dir.listFiles()?.sortedBy { it.name } ?: continue
            for (file in children) {
                if (hits.size >= maxResults || totalBytes >= maxTotalBytes) break
                when {
                    file.isDirectory -> if (pending.size < 8192) pending.addLast(file)
                    file.isFile && isText(file) -> {
                        if (file.name.lowercase().contains(needleLower)) {
                            hits.add(SearchHit(relative(root, file), 0, "(filename match)"))
                            totalBytes += 24
                            continue
                        }
                        if (file.length() > maxFileBytes) continue
                        val text = runCatching { file.readText() }.getOrNull() ?: continue
                        if (totalBytes + text.length > maxTotalBytes) continue
                        text.lineSequence().forEachIndexed { index, line ->
                            if (hits.size >= maxResults) return@forEachIndexed
                            if (line.lowercase().contains(needleLower)) {
                                val snippet = line.trim().take(200)
                                hits.add(SearchHit(relative(root, file), index + 1, snippet))
                            }
                        }
                        totalBytes += text.length.toLong().coerceAtMost(maxFileBytes)
                    }
                }
            }
        }
        if (hits.isEmpty()) return "(no matches)"
        return buildString {
            hits.forEach { hit ->
                append(hit.path)
                if (hit.line > 0) append(':').append(hit.line)
                append(": ").append(hit.snippet).append('\n')
            }
        }.trimEnd()
    }

    /**
     * Reads a single text file inside the workspace, capped at [maxBytes]. Returns a denial reason
     * (fail-closed) when the path is unsafe, the file is binary/oversized, or the read fails.
     */
    fun readFile(root: File, relativePath: String, maxBytes: Int = DEFAULT_READ_BYTES): String {
        val resolved = when (val r = WorkspacePathGuard.resolve(root, relativePath)) {
            is WorkspacePathGuard.Resolved.Denied -> return "Denied: ${r.reason}"
            is WorkspacePathGuard.Resolved.Ok -> r.file
        }
        if (!WorkspacePathGuard.isSafeReadFile(root, resolved)) return "Denied: not a readable file in the workspace."
        if (!isText(resolved)) return "Denied: binary file."
        if (resolved.length() > maxBytes) return "Denied: file exceeds the ${maxBytes}-byte read limit."
        val text = runCatching { resolved.readText() }.getOrNull()
            ?: return "Denied: could not read file."
        return text.take(maxBytes)
    }

    private fun isText(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in BINARY_EXTENSIONS) return false
        if (ext in TEXT_EXTENSIONS) return true
        return false
    }

    private fun relative(root: File, file: File): String =
        runCatching { file.toRelativeString(root) }.getOrDefault(file.name)
}
