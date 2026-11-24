package com.mobileharness.code.research

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchTest {

    private fun engine(responses: Map<String, List<SearchResult>>) = DeepResearchEngine(
        search = { query ->
            responses[query] ?: responses.entries.firstOrNull { query.contains(it.key) }?.value ?: emptyList()
        },
    )

    private fun results(vararg urls: String): List<SearchResult> = urls.mapIndexed { i, url ->
        SearchResult("Source $i: $url", url, "Relevant snippet about Android development and licensing.", ResearchSource.TAVILY)
    }

    @Test fun depthLevelsHaveExpectedCaps() {
        assertEquals(5, ResearchDepth.QUICK.maxSources)
        assertEquals(10, ResearchDepth.STANDARD.maxSources)
        assertEquals(20, ResearchDepth.DEEP.maxSources)
        assertEquals(50, ResearchDepth.EXTREME.maxSources)
    }

    @Test fun deduplicatesSourcesByUrl() = runBlocking {
        val e = engine(mapOf("android" to listOf(
            SearchResult("A", "https://x.example.com", "s"),
            SearchResult("A again", "https://x.example.com/", "s"),
            SearchResult("B", "https://y.example.com", "s"),
        )))
        val report = e.research("android", ResearchDepth.QUICK)
        assertEquals(2, report.sources.size)
    }

    @Test fun deriveFollowUpsUsesQueryAndTitles() {
        val e = DeepResearchEngine(search = { emptyList() })
        val followUps = e.deriveFollowUps(
            "android gradle build",
            listOf(
                SearchResult("Gradle build cache optimization", "https://a.example.com", ""),
                SearchResult("Android AGP version compatibility", "https://b.example.com", ""),
                SearchResult("Another result here", "https://c.example.com", ""),
            ),
        )
        assertTrue(followUps.isNotEmpty())
        // follow-ups should be derived from base terms + title terms
        assertTrue(followUps.any { it.contains("gradle") })
    }

    @Test fun reportRendersSourcesAndConfidence() = runBlocking {
        val e = engine(mapOf("android" to results("https://a.example.com", "https://b.example.com")))
        val report = e.research("android", ResearchDepth.STANDARD)
        val rendered = report.render()
        assertTrue(rendered.contains("Deep research: android"))
        assertTrue(rendered.contains("Sources (2)"))
        assertTrue(rendered.contains("https://a.example.com"))
        assertTrue(rendered.contains("confidence"))
    }

    @Test fun emptyResultsProduceUnconfirmedReport() = runBlocking {
        val e = engine(emptyMap())
        val report = e.research("nothing", ResearchDepth.QUICK)
        assertEquals(0, report.sources.size)
        assertEquals(ResearchConfidence.UNCONFIRMED, report.confidence)
    }
}
