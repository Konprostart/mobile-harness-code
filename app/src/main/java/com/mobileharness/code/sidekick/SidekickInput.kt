package com.mobileharness.code.sidekick

/** An image ready to be sent as a multimodal message block to a vision-capable model. */
data class SidekickImage(
    val displayName: String,
    val mimeType: String,
    val base64: String,
)

/** A text file whose content is inlined into the user message for analysis. */
data class SidekickFile(
    val displayName: String,
    val mimeType: String,
    val text: String,
)

/**
 * Pure input helpers for the Sidekick assistant: attachment classification, size guards and
 * message building. Kept free of Android types so the logic is unit-testable on the JVM.
 */
object SidekickInput {
    /** Upper bound for text inlined from a single attached file, keeps prompts bounded. */
    const val MAX_FILE_TEXT_CHARS = 24_000

    /** Upper bound for a single image's base64 payload (~3 MiB of decoded image data). */
    const val MAX_IMAGE_BASE64_CHARS = 4 * 1024 * 1024

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
        "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
        "gradle", "properties", "toml", "ini", "conf", "sql",
    )

    /** Whether an attachment is a text file whose content can be inlined into a prompt. */
    fun isTextFile(displayName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("text/") ||
            mimeType == "application/json" ||
            mimeType == "application/xml" ||
            mimeType.endsWith("+json") ||
            mimeType.endsWith("+xml")
        ) return true
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    /** Normalizes a source MIME type to a vision-model image MIME, or null when unsupported. */
    fun normalizeImageMime(mimeType: String?, displayName: String): String? {
        val mt = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when {
            mt == "image/png" || ext == "png" -> "image/png"
            mt == "image/webp" || ext == "webp" -> "image/webp"
            mt == "image/gif" || ext == "gif" -> "image/gif"
            mt.startsWith("image/") || ext == "jpg" || ext == "jpeg" -> "image/jpeg"
            else -> null
        }
    }

    /** Truncates file text so a single attachment can never blow up the prompt. */
    fun truncateFileText(text: String, maxChars: Int = MAX_FILE_TEXT_CHARS): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "\n…[truncated]"

    /** Builds the final user message, inlining the content of any attached text files. */
    fun buildUserMessage(question: String, files: List<SidekickFile>): String {
        val q = question.trim()
        if (files.isEmpty()) return q
        return buildString {
            append(q)
            for (file in files) {
                append("\n\n--- Attached file: ").append(file.displayName)
                append(" (").append(file.mimeType.ifBlank { "text/plain" }).append(") ---\n")
                append(truncateFileText(file.text))
            }
        }
    }
}
