package com.mobileharness.code.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTest {

    @Test
    fun `claim check verifies supported claim`() {
        val claim = "the app runs on arm64 android devices"
        val results = listOf(
            SearchResult("Docs", "https://example.com", "the app runs on arm64 android devices with a full toolchain"),
        )
        assertEquals(ClaimVerdict.VERIFIED, ClaimCheck.assess(claim, results).verdict)
    }

    @Test
    fun `claim check unknown without evidence`() {
        val claim = "the app runs on arm64 android devices"
        assertEquals(ClaimVerdict.UNKNOWN, ClaimCheck.assess(claim, emptyList()).verdict)
    }

    @Test
    fun `claim check disputed when supporting and partial sources mix`() {
        val claim = "the mobile harness app runs on android arm64 devices with a linux runtime"
        val results = listOf(
            SearchResult("A", "https://a.com", "the mobile harness app runs on android arm64 devices with a linux runtime"),
            SearchResult("B", "https://b.com", "the mobile harness app"),
        )
        assertEquals(ClaimVerdict.DISPUTED, ClaimCheck.assess(claim, results).verdict)
    }

    @Test
    fun `extract claims splits sentences`() {
        val claims = ClaimCheck.extractClaims("Feature one is done. Feature two is also done. Short.")
        assertTrue(claims.size >= 2)
        assertTrue(claims.any { it.startsWith("Feature one") })
    }

    @Test
    fun `parse results handles flat json`() {
        val json = """[{"title":"T","url":"https://a.com","snippet":"s"}]"""
        val results = ClaimCheck.parseResults(json, ResearchSource.JINA)
        assertEquals(1, results.size)
        assertEquals("https://a.com", results.first().url)
    }

    @Test
    fun `policy trims and dedupes`() {
        val results = listOf(
            SearchResult("A", "https://a.com/"),
            SearchResult("A", "https://a.com"),
            SearchResult("B", "https://b.com"),
        )
        val trimmed = ResearchPolicy.trim(results, 5)
        assertEquals(2, trimmed.size)
    }

    @Test
    fun `policy rejects remote plain http`() {
        assertTrue(!ResearchPolicy.isAllowedUrl("http://example.com"))
        assertTrue(ResearchPolicy.isAllowedUrl("https://example.com"))
    }
}
