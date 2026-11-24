package com.mobileharness.code.research

import kotlin.math.ln

/**
 * BM25 passage ranking for research pages: picks the parts of read pages that answer a query, so
 * the model gets a few thousand relevant characters from several sites instead of whole pages.
 *
 * Ported and adapted from the AndroidHarness `SearchPassages` ranking (MIT, (c) 2026 Sanuu7).
 * See THIRD_PARTY_NOTICES.md / NOTICE.
 */
object TextRanking {
    private const val CHUNK_CHARS = 700
    private const val MIN_CHUNK_CHARS = 80
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private val stopwords = setOf(
        "the", "and", "for", "are", "was", "were", "with", "that", "this", "from", "what", "which", "who",
        "how", "why", "when", "where", "does", "did", "can", "could", "should", "would", "will", "about",
        "into", "than", "then", "there", "their", "they", "them", "have", "has", "had", "you", "your",
        "its", "not", "but", "any", "all", "out", "our", "is", "of", "to", "in", "on", "at", "by", "an",
        "a", "or", "as", "be", "it", "do", "vs", "me", "my", "i",
    )

    /** The passages one read page contributed, in page order. */
    data class PagePassages(val result: SearchResult, val passages: List<String>)

    fun terms(query: String): List<String> =
        wordPattern.findAll(query.lowercase()).map { it.value }
            .filter { it !in stopwords && (it.length > 1 || it.first().isDigit()) }
            .distinct().toList()

    /** Paragraphs merged up to a readable size; very long paragraphs split at sentence ends. */
    fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val chunk = current.toString().trim()
            if (chunk.length >= MIN_CHUNK_CHARS) out += chunk
            current.clear()
        }
        for (raw in text.split(Regex("\\n+"))) {
            val paragraph = raw.trim()
            if (paragraph.isEmpty()) continue
            if (paragraph.length > CHUNK_CHARS) {
                flush()
                val sentences = paragraph.split(Regex("(?<=[.!?])\\s+"))
                for (sentence in sentences) {
                    if (current.length + sentence.length > CHUNK_CHARS && current.isNotEmpty()) flush()
                    current.append(sentence.take(CHUNK_CHARS * 2)).append(' ')
                }
                flush()
                continue
            }
            if (current.length + paragraph.length > CHUNK_CHARS && current.isNotEmpty()) flush()
            current.append(paragraph).append('\n')
        }
        flush()
        return out
    }

    /**
     * Ranks every page's chunks against [query]. Each page with any match keeps its best chunk, so
     * one long page cannot crowd out other sites; the rest of the room goes to the highest scores,
     * at most [perPage] per page.
     */
    fun rank(
        query: String,
        pages: List<Pair<SearchResult, String>>,
        maxPassages: Int = 12,
        perPage: Int = 3,
        maxChars: Int = 9_000,
    ): List<PagePassages> {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty() || pages.isEmpty()) return emptyList()
        data class Chunk(val page: Int, val index: Int, val text: String, val tf: Map<String, Int>, val length: Int)
        val all = pages.flatMapIndexed { pageIndex, (_, text) ->
            chunks(text).mapIndexed { index, chunk ->
                val words = wordPattern.findAll(chunk.lowercase()).map { it.value }.toList()
                Chunk(pageIndex, index, chunk, words.filter { it in queryTerms }.groupingBy { it }.eachCount(), words.size.coerceAtLeast(1))
            }
        }
        if (all.isEmpty()) return emptyList()
        val averageLength = all.sumOf { it.length }.toDouble() / all.size
        val idf = queryTerms.associateWith { term ->
            val containing = all.count { term in it.tf }
            ln(1.0 + (all.size - containing + 0.5) / (containing + 0.5))
        }
        val k1 = 1.2
        val b = 0.75
        fun score(chunk: Chunk): Double = chunk.tf.entries.sumOf { (term, tf) ->
            val norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * chunk.length / averageLength))
            (idf[term] ?: 0.0) * norm
        } * (0.5 + 0.5 * chunk.tf.size / queryTerms.size) // favour chunks that cover more of the question
        val scored = all.map { it to score(it) }.filter { it.second > 0.0 }.sortedByDescending { it.second }

        val chosen = LinkedHashSet<Chunk>()
        val perPageCount = HashMap<Int, Int>()
        var chars = 0
        fun take(chunk: Chunk): Boolean {
            if (chosen.size >= maxPassages || chars + chunk.text.length > maxChars) return false
            if ((perPageCount[chunk.page] ?: 0) >= perPage || !chosen.add(chunk)) return false
            perPageCount[chunk.page] = (perPageCount[chunk.page] ?: 0) + 1
            chars += chunk.text.length
            return true
        }
        scored.distinctBy { it.first.page }.forEach { take(it.first) }
        scored.forEach { take(it.first) }

        return pages.indices.mapNotNull { pageIndex ->
            val passages = chosen.filter { it.page == pageIndex }.sortedBy { it.index }.map { it.text }
            if (passages.isEmpty()) null else PagePassages(pages[pageIndex].first, passages)
        }
    }

    /** Simple relevance score for whole results (title + snippet overlap with the query terms). */
    fun relevance(query: String, result: SearchResult): Double {
        val q = terms(query)
        if (q.isEmpty()) return 0.0
        val haystack = "${result.title} ${result.snippet}".lowercase()
        val matched = q.count { haystack.contains(it) }
        return matched.toDouble() / q.size
    }
}
