package com.mobileharness.code.sidekick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagIndexTest {
    private fun doc(id: String, title: String, text: String) = RagDocument(id, title, text, "")

    @Test
    fun chunkReturnsEmptyForBlankText() {
        assertEquals(emptyList<String>(), RagIndex.chunk(""))
        assertEquals(emptyList<String>(), RagIndex.chunk("   \n\t  "))
    }

    @Test
    fun chunkKeepsShortTextAsSingleChunk() {
        val text = "A short document."
        assertEquals(listOf(text), RagIndex.chunk(text))
    }

    @Test
    fun chunkSplitsLongTextIntoOverlappingWindows() {
        val word = "word"
        // Build a long text of repeated words so length exceeds CHUNK_SIZE.
        val text = List(300) { word }.joinToString(" ")
        val chunks = RagIndex.chunk(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= RagIndex.CHUNK_SIZE })
        assertTrue(chunks.all { it.isNotBlank() })
        // Consecutive chunks overlap by exactly CHUNK_OVERLAP characters.
        val first = chunks[0]
        val second = chunks[1]
        assertEquals(
            first.substring(first.length - RagIndex.CHUNK_OVERLAP),
            second.substring(0, RagIndex.CHUNK_OVERLAP),
        )
    }

    @Test
    fun scoreIsZeroForBlankQueryOrChunk() {
        assertEquals(0.0, RagIndex.score("", "anything"), 0.0)
        assertEquals(0.0, RagIndex.score("query", ""), 0.0)
    }

    @Test
    fun scoreFavorsChunksContainingQueryTerms() {
        val query = "kotlin gradle android"
        val matching = RagIndex.score(query, "This project builds an android app with gradle and kotlin.")
        val unrelated = RagIndex.score(query, "The quick brown fox jumps over the lazy dog.")
        assertTrue(matching > 0.0)
        assertTrue(matching > unrelated)
    }

    @Test
    fun retrieveReturnsTopRankedChunksSortedByScore() {
        val documents = listOf(
            doc("a", "A", "kotlin gradle android build on device"),
            doc("b", "B", "recipes for cooking pasta and risotto"),
            doc("c", "C", "kotlin coroutines and android compose"),
        )
        val chunks = RagIndex.retrieve("kotlin android", documents, topK = 2)
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].score >= chunks[1].score)
        assertTrue(chunks.all { it.documentId != "b" })
    }

    @Test
    fun retrieveRespectsMaxChars() {
        val documents = listOf(
            doc("a", "A", "kotlin ".repeat(200)),
            doc("b", "B", "kotlin ".repeat(200)),
        )
        val chunks = RagIndex.retrieve("kotlin", documents, topK = 10, maxChars = 300)
        val total = chunks.sumOf { it.text.length }
        assertTrue(total <= 300)
    }

    @Test
    fun retrieveDropsZeroScoreChunks() {
        val documents = listOf(doc("a", "A", "unrelated content"))
        assertEquals(emptyList<RagChunk>(), RagIndex.retrieve("kotlin", documents))
    }

    @Test
    fun buildContextIncludesTitlesAndText() {
        val chunks = listOf(RagChunk("a", "My Doc", "passage one", 1.0))
        val context = RagIndex.buildContext(chunks)
        assertTrue(context.contains("My Doc"))
        assertTrue(context.contains("passage one"))
    }

    @Test
    fun buildContextIsEmptyForNoChunks() {
        assertEquals("", RagIndex.buildContext(emptyList()))
    }
}
