package com.mobileharness.code.research

import org.json.JSONObject

/**
 * A flexible JSON search connector that supports GET ({query} template) or POST, a dot-separated
 * result path, and either a header or query/body API key. It normalizes to [SearchResult] and is
 * transport-injected so wire formats are unit-testable without a network.
 */
class ProviderSearchConnector(
    override val source: ResearchSource,
    private val endpoint: String,
    private val resultPath: String,
    private val queryParam: String = "query",
    private val authHeader: String? = null,
    private val authPrefix: String = "Bearer ",
    private val keyInQueryParam: String? = null,
    private val transport: JsonSearchConnector.Transport = HttpTransport,
) : ResearchConnector {
    override fun requiresKey(): Boolean = authHeader != null || keyInQueryParam != null

    override suspend fun search(query: String, apiKey: String?, maxResults: Int): List<SearchResult> {
        val headers = buildMap {
            if (authHeader != null && !apiKey.isNullOrBlank()) {
                put(authHeader, authPrefix + apiKey)
            }
            put("Accept", "application/json")
        }
        val response = try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (keyInQueryParam != null && !apiKey.isNullOrBlank()) {
                endpoint.replace("{query}", encoded).replace("{key}", java.net.URLEncoder.encode(apiKey, "UTF-8"))
            } else {
                endpoint.replace("{query}", encoded)
            }
            if (endpoint.contains("{query}")) {
                transport.get(url, headers)
            } else {
                val body = JSONObject().put(queryParam, query).toString()
                transport.postJson(endpoint, headers, body)
            }
        } catch (e: Exception) {
            return emptyList()
        }
        if (response.code !in 200..299) return emptyList()
        val results = ClaimCheck.parseResultsDeep(response.body, resultPath, source)
        return ResearchPolicy.trim(results, maxResults)
    }
}

/**
 * Keyless DuckDuckGo (HTML "lite" endpoint) connector. Parses result links and snippets from the
 * HTML response; no API key is required. This is a best-effort public interface subject to DDG's
 * own terms and rate limits, so it is treated as the lowest-priority fallback.
 */
class DuckDuckGoConnector(
    private val endpoint: String = "https://lite.duckduckgo.com/lite/?q={query}",
    private val transport: JsonSearchConnector.Transport = HttpTransport,
) : ResearchConnector {
    override val source: ResearchSource = ResearchSource.DUCKDUCKGO
    override fun requiresKey(): Boolean = false

    override suspend fun search(query: String, apiKey: String?, maxResults: Int): List<SearchResult> {
        val response = try {
            transport.get(endpoint.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8")), mapOf("Accept" to "text/html"))
        } catch (e: Exception) {
            return emptyList()
        }
        if (response.code !in 200..299) return emptyList()
        return ResearchPolicy.trim(parseHtml(response.body), maxResults)
    }

    private fun parseHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // lite.duckduckgo.com result links: <a rel="nofollow" href="..." class="result-link">Title</a>
        val linkRegex = Regex("""<a[^>]+href="([^"]+)"[^>]*class="[^"]*result-link[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val snippetRegex = Regex("""<td[^>]*class="[^"]*result-snippet[^"]*"[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { htmlToText(it.groupValues[1]) }.toList()
        links.forEachIndexed { i, match ->
            val url = htmlToText(match.groupValues[1])
            val title = htmlToText(match.groupValues[2])
            if (url.startsWith("http")) {
                results += SearchResult(
                    title = title.ifBlank { url },
                    url = url,
                    snippet = snippets.getOrNull(i).orEmpty(),
                    source = source,
                )
            }
        }
        return results
    }

    private fun htmlToText(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("&#39;"), "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Additional connector presets for the documented public endpoints. */
object AdditionalPresets {
    fun tavily(): ResearchConnector = ProviderSearchConnector(
        source = ResearchSource.TAVILY,
        endpoint = "https://api.tavily.com/search",
        resultPath = "results",
        queryParam = "query",
        authHeader = "Authorization",
        authPrefix = "Bearer ",
    )

    fun brave(): ResearchConnector = ProviderSearchConnector(
        source = ResearchSource.BRAVE,
        endpoint = "https://api.search.brave.com/res/v1/web/search?q={query}",
        resultPath = "web.results",
        queryParam = "q",
        authHeader = "X-Subscription-Token",
        authPrefix = "",
    )

    fun serper(): ResearchConnector = ProviderSearchConnector(
        source = ResearchSource.SERPER,
        endpoint = "https://google.serper.dev/search",
        resultPath = "organic",
        queryParam = "q",
        authHeader = "X-API-KEY",
        authPrefix = "",
    )

    fun serpapi(): ResearchConnector = ProviderSearchConnector(
        source = ResearchSource.SERPAPI,
        endpoint = "https://serpapi.com/search.json?q={query}&api_key={key}",
        resultPath = "organic_results",
        queryParam = "q",
        keyInQueryParam = "api_key",
    )

    fun duckduckgo(): ResearchConnector = DuckDuckGoConnector()
}

/** A recorded attempt (success or failure) of one connector during a research run. */
data class ResearchAttempt(
    val source: ResearchSource,
    val success: Boolean,
    val resultCount: Int,
    val timestampMillis: Long,
)

/** Bounded, append-only audit of connector attempts (no secrets, no query bodies). */
class ResearchAuditLog(private val maxEntries: Int = 500) {
    private val entries = ArrayDeque<ResearchAttempt>()

    @Synchronized
    fun record(attempt: ResearchAttempt) {
        entries.addLast(attempt)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun entries(): List<ResearchAttempt> = entries.toList()

    @Synchronized
    fun successRate(): Double {
        if (entries.isEmpty()) return 0.0
        return entries.count { it.success }.toDouble() / entries.size
    }
}

/**
 * Orchestrates multiple connectors with priority + fallback + auto-selection. It never returns
 * results from a connector that failed; it walks the [priority] list, skips sources whose key is
 * missing (when required), and returns the first non-empty result set, recording every attempt.
 */
class ResearchProviderRegistry(
    private val registry: ResearchConnectorRegistry,
) {
    /**
     * Searches with priority + fallback. [keys] maps a source to its API key. [priority] is the
     * ordered list to try (highest priority first); sources not in it are never attempted here.
     * Returns the first non-empty result set, or empty when every source failed.
     */
    suspend fun search(
        query: String,
        keys: Map<ResearchSource, String>,
        priority: List<ResearchSource>,
        maxResults: Int,
        audit: ResearchAuditLog? = null,
    ): List<SearchResult> {
        for (source in priority) {
            val connector = registry.get(source) ?: continue
            val apiKey = keys[source]
            if (connector.requiresKey() && apiKey.isNullOrBlank()) {
                audit?.record(ResearchAttempt(source, success = false, resultCount = 0, timestampMillis = System.currentTimeMillis()))
                continue
            }
            val results = connector.search(query, apiKey, maxResults)
            audit?.record(ResearchAttempt(source, success = results.isNotEmpty(), resultCount = results.size, timestampMillis = System.currentTimeMillis()))
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    /** Returns the highest-priority source in [priority] that is usable (present + keyed if required). */
    fun autoSelect(keys: Map<ResearchSource, String>, priority: List<ResearchSource>): ResearchSource? {
        return priority.firstOrNull { source ->
            val connector = registry.get(source) ?: return@firstOrNull false
            !connector.requiresKey() || !keys[source].isNullOrBlank()
        }
    }
}

/** Builds the default connector registry with every supported source. */
object ResearchRegistryFactory {
    fun build(): ResearchConnectorRegistry {
        val registry = ResearchConnectorRegistry()
        registry.register(ConnectorPresets.marginalia())
        registry.register(ConnectorPresets.jina())
        registry.register(ConnectorPresets.firecrawl())
        registry.register(ConnectorPresets.linkup())
        registry.register(ConnectorPresets.langsearch())
        registry.register(AdditionalPresets.tavily())
        registry.register(AdditionalPresets.brave())
        registry.register(AdditionalPresets.serper())
        registry.register(AdditionalPresets.serpapi())
        registry.register(AdditionalPresets.duckduckgo())
        return registry
    }
}
