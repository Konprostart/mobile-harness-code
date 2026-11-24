package com.mobileharness.code.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport(
    var getResponse: JsonSearchConnector.Response = JsonSearchConnector.Response(404, ""),
    var postResponse: JsonSearchConnector.Response = JsonSearchConnector.Response(404, ""),
) : JsonSearchConnector.Transport {
    val gets = mutableListOf<Pair<String, Map<String, String>>>()
    val posts = mutableListOf<Pair<String, Map<String, String>>>()
    override suspend fun get(url: String, headers: Map<String, String>): JsonSearchConnector.Response {
        gets += url to headers
        return getResponse
    }
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String): JsonSearchConnector.Response {
        posts += url to headers
        return postResponse
    }
}

class ResearchProvidersTest {

    private val sampleResults = """[
        {"title":"Result A","url":"https://a.example.com","snippet":"first"},
        {"title":"Result B","url":"https://b.example.com","snippet":"second"}
    ]"""

    @Test fun tavilyUsesPostAndBearer() = kotlinx.coroutines.runBlocking {
        val transport = FakeTransport(postResponse = JsonSearchConnector.Response(200, """{"results":$sampleResults}"""))
        val connector = AdditionalPresets.tavily() as ProviderSearchConnector
        // Replace the default transport by rebuilding with the fake via reflection-free factory:
        val withTransport = ProviderSearchConnector(
            ResearchSource.TAVILY, "https://api.tavily.com/search", "results", "query", "Authorization", "Bearer ", null, transport,
        )
        val results = withTransport.search("test query", "secret", 10)
        assertEquals(2, results.size)
        assertEquals(1, transport.posts.size)
        assertEquals("Bearer secret", transport.posts[0].second["Authorization"])
    }

    @Test fun braveUsesSubscriptionTokenAndNestedPath() = kotlinx.coroutines.runBlocking {
        val transport = FakeTransport(getResponse = JsonSearchConnector.Response(200, """{"web":{"results":$sampleResults}}"""))
        val connector = ProviderSearchConnector(
            ResearchSource.BRAVE, "https://api.search.brave.com/res/v1/web/search?q={query}", "web.results", "q", "X-Subscription-Token", "", null, transport,
        )
        val results = connector.search("test query", "tok", 10)
        assertEquals(2, results.size)
        assertEquals(1, transport.gets.size)
        assertEquals("tok", transport.gets[0].second["X-Subscription-Token"])
    }

    @Test fun serperUsesOrganicResults() = kotlinx.coroutines.runBlocking {
        val transport = FakeTransport(postResponse = JsonSearchConnector.Response(200, """{"organic":$sampleResults}"""))
        val connector = ProviderSearchConnector(
            ResearchSource.SERPER, "https://google.serper.dev/search", "organic", "q", "X-API-KEY", "", null, transport,
        )
        val results = connector.search("test query", "key", 10)
        assertEquals(2, results.size)
        assertEquals("key", transport.posts[0].second["X-API-KEY"])
    }

    @Test fun serpapiUsesKeyQueryParam() = kotlinx.coroutines.runBlocking {
        val transport = FakeTransport(getResponse = JsonSearchConnector.Response(200, """{"organic_results":$sampleResults}"""))
        val connector = ProviderSearchConnector(
            ResearchSource.SERPAPI, "https://serpapi.com/search.json?q={query}&api_key={key}", "organic_results", "q", null, "", "api_key", transport,
        )
        val results = connector.search("test query", "mykey", 10)
        assertEquals(2, results.size)
        assertTrue(transport.gets[0].first.contains("api_key=mykey"))
    }

    @Test fun duckduckgoParsesHtmlKeyless() = kotlinx.coroutines.runBlocking {
        val html = """
            <html><body>
            <a rel="nofollow" href="https://example.com/page" class="result-link">Example Page</a>
            <td class="result-snippet">A snippet about example.</td>
            <a rel="nofollow" href="https://example.org/x" class="result-link">Example Org</a>
            <td class="result-snippet">Another snippet.</td>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport(getResponse = JsonSearchConnector.Response(200, html))
        val connector = DuckDuckGoConnector(transport = transport)
        assertEquals(false, connector.requiresKey())
        val results = connector.search("example", null, 10)
        assertEquals(2, results.size)
        assertEquals("Example Page", results[0].title)
        assertEquals("https://example.com/page", results[0].url)
    }

    @Test fun registryFallsBackToNextProvider() = kotlinx.coroutines.runBlocking {
        val registry = ResearchConnectorRegistry()
        val failing = ProviderSearchConnector(
            ResearchSource.TAVILY, "https://api.tavily.com/search", "results", "query", "Authorization", "Bearer ", null,
            FakeTransport(postResponse = JsonSearchConnector.Response(200, """{"results":[]}""")),
        )
        val working = ProviderSearchConnector(
            ResearchSource.SERPER, "https://google.serper.dev/search", "organic", "q", "X-API-KEY", "", null,
            FakeTransport(postResponse = JsonSearchConnector.Response(200, """{"organic":$sampleResults}""")),
        )
        registry.register(failing)
        registry.register(working)

        val providerRegistry = ResearchProviderRegistry(registry)
        val audit = ResearchAuditLog()
        val results = providerRegistry.search(
            "test",
            mapOf(ResearchSource.TAVILY to "k1", ResearchSource.SERPER to "k2"),
            listOf(ResearchSource.TAVILY, ResearchSource.SERPER),
            10,
            audit,
        )
        assertEquals(2, results.size)
        assertEquals(2, audit.entries().size)
        assertEquals(false, audit.entries()[0].success)
        assertEquals(true, audit.entries()[1].success)
    }

    @Test fun registrySkipsMissingKeyProviders() = kotlinx.coroutines.runBlocking {
        val registry = ResearchConnectorRegistry()
        registry.register(
            ProviderSearchConnector(
                ResearchSource.TAVILY, "https://api.tavily.com/search", "results", "query", "Authorization", "Bearer ", null,
                FakeTransport(postResponse = JsonSearchConnector.Response(200, """{"results":$sampleResults}""")),
            ),
        )
        val providerRegistry = ResearchProviderRegistry(registry)
        val results = providerRegistry.search("test", emptyMap(), listOf(ResearchSource.TAVILY), 10)
        assertEquals(0, results.size) // no key → skipped, never called
    }

    @Test fun autoSelectPicksFirstUsableSource() {
        val registry = ResearchConnectorRegistry()
        registry.register(AdditionalPresets.tavily()) // requires key
        registry.register(AdditionalPresets.duckduckgo()) // keyless
        val providerRegistry = ResearchProviderRegistry(registry)
        assertEquals(ResearchSource.TAVILY, providerRegistry.autoSelect(mapOf(ResearchSource.TAVILY to "k"), listOf(ResearchSource.TAVILY, ResearchSource.DUCKDUCKGO)))
        assertEquals(ResearchSource.DUCKDUCKGO, providerRegistry.autoSelect(emptyMap(), listOf(ResearchSource.TAVILY, ResearchSource.DUCKDUCKGO)))
    }

    @Test fun factoryBuildsAllTenConnectors() {
        val registry = ResearchRegistryFactory.build()
        assertEquals(10, registry.all().size)
    }
}
