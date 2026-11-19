package com.mobileharness.code.sidekick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagCodecTest {
    @Test
    fun roundTripsDocuments() {
        val documents = listOf(
            RagDocument("1", "First", "hello world", "/tmp/first.txt"),
            RagDocument("2", "Second", "kotlin gradle", ""),
        )
        assertEquals(documents, RagCodec.fromJson(RagCodec.toJson(documents)))
    }

    @Test
    fun blankJsonReturnsEmptyList() {
        assertEquals(emptyList<RagDocument>(), RagCodec.fromJson(""))
        assertEquals(emptyList<RagDocument>(), RagCodec.fromJson("   "))
    }

    @Test
    fun malformedJsonReturnsEmptyList() {
        assertEquals(emptyList<RagDocument>(), RagCodec.fromJson("not json at all"))
        assertEquals(emptyList<RagDocument>(), RagCodec.fromJson("{\"id\":\"1\"}"))
    }

    @Test
    fun dropsInvalidEntriesAndKeepsValidOnes() {
        val json = """
            [
              {"id":"1","title":"Good","text":"hello","source":""},
              {"title":"missing id","text":"nope"},
              {"id":"2","title":"Blank text","text":"   ","source":""}
            ]
        """.trimIndent()
        val documents = RagCodec.fromJson(json)
        assertEquals(1, documents.size)
        assertEquals("1", documents[0].id)
    }

    @Test
    fun truncatesOversizedTextOnLoad() {
        val longText = "x".repeat(RagIndex.MAX_DOC_TEXT_CHARS + 500)
        val json = RagCodec.toJson(listOf(RagDocument("1", "Big", longText, "")))
        val documents = RagCodec.fromJson(json)
        assertEquals(RagIndex.MAX_DOC_TEXT_CHARS, documents[0].text.length)
        assertTrue(documents[0].text.all { it == 'x' })
    }
}
