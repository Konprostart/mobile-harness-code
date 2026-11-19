package com.mobileharness.code.sidekick

/**
 * Pure, dependency-free document base ("RAG") for the Sidekick assistant, inspired by the
 * AnythingLLM idea but implemented as a tiny on-device keyword index: no embedding service, no
 * vector database and no network access. The user saves small text documents; at question time
 * the engine chunks them, scores each chunk against the query with a deterministic token-overlap
 * score, and injects only the most relevant passages into the assistant prompt.
 *
 * Everything here is side-effect free so chunking, scoring and retrieval are unit-testable
 * without an Android context.
 */

/** A single saved document in the assistant's document base. */
data class RagDocument(
    val id: String,
    val title: String,
    val text: String,
    val source: String,
)

/** A retrieved passage with its origin and relevance score. */
data class RagChunk(
    val documentId: String,
    val title: String,
    val text: String,
    val score: Double,
)

object RagIndex {
    /** Hard cap on the number of saved documents. */
    const val MAX_DOCUMENTS = 32

    /** Hard cap on a single document's text (characters). */
    const val MAX_DOC_TEXT_CHARS = 64_000

    /** Sliding-window chunk size in characters. */
    const val CHUNK_SIZE = 800

    /** Overlap between consecutive chunks so a sentence is not split at the boundary. */
    const val CHUNK_OVERLAP = 100

    /** Number of chunks injected by default. */
    const val DEFAULT_TOP_K = 4

    /** Total retrieved characters cap in normal mode. */
    const val MAX_RETRIEVED_CHARS = 4_000

    /**
     * Splits [text] into overlapping character windows. Whitespace is first collapsed so a
     * document is not dominated by indentation. Short text yields a single chunk.
     */
    fun chunk(text: String): List<String> {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return emptyList()
        if (collapsed.length <= CHUNK_SIZE) return listOf(collapsed)
        val step = (CHUNK_SIZE - CHUNK_OVERLAP).coerceAtLeast(1)
        val result = mutableListOf<String>()
        var start = 0
        while (start < collapsed.length) {
            result += collapsed.substring(start, minOf(start + CHUNK_SIZE, collapsed.length))
            if (start + CHUNK_SIZE >= collapsed.length) break
            start += step
        }
        return result
    }

    /** Lowercased alphanumeric word tokens; deterministic and locale-independent. */
    fun tokenize(text: String): List<String> = text.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }

    /**
     * Relevance of [chunkText] to [query]: a sublinear term-frequency sum over the distinct query
     * tokens, normalized by chunk length so long chunks are not unfairly favored. Returns 0 for a
     * blank query or a chunk with no overlap.
     */
    fun score(query: String, chunkText: String): Double {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return 0.0
        val chunkTokens = tokenize(chunkText)
        if (chunkTokens.isEmpty()) return 0.0
        val counts = chunkTokens.groupingBy { it }.eachCount()
        var total = 0.0
        for (token in queryTokens.distinct()) {
            val tf = counts[token] ?: 0
            if (tf > 0) total += 1.0 + Math.log(tf.toDouble())
        }
        if (total <= 0.0) return 0.0
        return total / Math.sqrt(chunkTokens.size.toDouble() + 1.0)
    }

    /**
     * Chunks every document, scores each chunk, returns the top-[topK] passages sorted by score
     * (descending) and bounded by [maxChars] total characters. Zero-score chunks are dropped.
     */
    fun retrieve(
        query: String,
        documents: List<RagDocument>,
        topK: Int = DEFAULT_TOP_K,
        maxChars: Int = MAX_RETRIEVED_CHARS,
    ): List<RagChunk> {
        val scored = mutableListOf<RagChunk>()
        for (document in documents.take(MAX_DOCUMENTS)) {
            for (textChunk in chunk(document.text.take(MAX_DOC_TEXT_CHARS))) {
                val chunkScore = score(query, textChunk)
                if (chunkScore > 0.0) {
                    scored += RagChunk(document.id, document.title, textChunk, chunkScore)
                }
            }
        }
        val ranked = scored.sortedByDescending { it.score }.take(topK.coerceAtLeast(1))
        if (maxChars <= 0) return emptyList()
        val result = mutableListOf<RagChunk>()
        var used = 0
        for (chunk in ranked) {
            if (used >= maxChars) break
            val remaining = maxChars - used
            val text = if (chunk.text.length > remaining) chunk.text.take(remaining) else chunk.text
            result += chunk.copy(text = text)
            used += text.length
        }
        return result
    }

    /** Formats the retrieved passages into a compact prompt fragment for the model. */
    fun buildContext(chunks: List<RagChunk>): String {
        if (chunks.isEmpty()) return ""
        return buildString {
            append(
                "Reference material from your document base (use it when relevant, and say " +
                    "clearly when it does not answer the question):\n",
            )
            chunks.forEach { chunk ->
                append("\n--- ").append(chunk.title.ifBlank { "Untitled document" }).append(" ---\n")
                append(chunk.text)
                append('\n')
            }
        }
    }
}
