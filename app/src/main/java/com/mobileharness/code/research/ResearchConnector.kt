package com.mobileharness.code.research

import org.json.JSONObject

/**
 * Web search connectors. Each connector is a thin HTTP client for one search service; all of them
 * normalize to [SearchResult] so the engine and claim checker are provider-agnostic. The transport
 * is injected so connector wire formats are unit-testable without a network.
 *
 * Services are HTTP endpoints (each with its own ToS/rate limits); no proprietary SDK is bundled.
 */
interface ResearchConnector {
    val source: ResearchSource
    fun requiresKey(): Boolean
    suspend fun search(query: String, apiKey: String?, maxResults: Int): List<SearchResult>
}

class ResearchConnectorRegistry {
    private val connectors = LinkedHashMap<ResearchSource, ResearchConnector>()

    fun register(connector: ResearchConnector) {
        connectors[connector.source] = connector
    }

    fun get(source: ResearchSource): ResearchConnector? = connectors[source]
    fun all(): List<ResearchConnector> = connectors.values.toList()
}

/**
 * A configurable JSON/GET connector. `searchUrl` is an optional template; when null the connector
 * issues a POST JSON body to [endpoint]. Response parsing is defensive and shared via [ClaimCheck].
 */
class JsonSearchConnector(
    override val source: ResearchSource,
    private val endpoint: String,
    private val resultKey: String?,
    private val queryParam: String,
    private val keyHeader: String = "Authorization",
    private val transport: Transport = HttpTransport,
) : ResearchConnector {
    interface Transport {
        suspend fun get(url: String, headers: Map<String, String>): Response
        suspend fun postJson(url: String, headers: Map<String, String>, body: String): Response
    }

    data class Response(val code: Int, val body: String)

    override fun requiresKey(): Boolean = true

    override suspend fun search(query: String, apiKey: String?, maxResults: Int): List<SearchResult> {
        val headers = buildMap {
            if (!apiKey.isNullOrBlank()) put(keyHeader, if (keyHeader == "Authorization") "Bearer $apiKey" else apiKey)
            put("Accept", "application/json")
        }
        val response = try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            if (endpoint.contains("{query}")) {
                transport.get(endpoint.replace("{query}", encoded), headers)
            } else {
                val body = JSONObject().put(queryParam, query).toString()
                transport.postJson(endpoint, headers, body)
            }
        } catch (e: Exception) {
            return emptyList()
        }
        if (response.code !in 200..299) return emptyList()
        val results = if (resultKey != null) {
            ClaimCheck.parseResultsNested(response.body, resultKey, source)
        } else {
            ClaimCheck.parseResults(response.body, source)
        }
        return ResearchPolicy.trim(results, maxResults)
    }
}

/** Standard connector presets for the documented public endpoints. */
object ConnectorPresets {
    fun marginalia(): ResearchConnector = JsonSearchConnector(
        source = ResearchSource.MARGINALIA,
        endpoint = "https://search.marginalia.nu/search?query={query}&format=json",
        resultKey = "results",
        queryParam = "query",
        keyHeader = "Authorization",
    )

    fun jina(): ResearchConnector = JsonSearchConnector(
        source = ResearchSource.JINA,
        endpoint = "https://s.jina.ai/{query}",
        resultKey = null,
        queryParam = "q",
        keyHeader = "Authorization",
    )

    fun firecrawl(): ResearchConnector = JsonSearchConnector(
        source = ResearchSource.FIRECRAWL,
        endpoint = "https://api.firecrawl.dev/v1/search",
        resultKey = "data",
        queryParam = "query",
        keyHeader = "Authorization",
    )

    fun linkup(): ResearchConnector = JsonSearchConnector(
        source = ResearchSource.LINKUP,
        endpoint = "https://api.linkup.so/v1/search",
        resultKey = "results",
        queryParam = "q",
        keyHeader = "Authorization",
    )

    fun langsearch(): ResearchConnector = JsonSearchConnector(
        source = ResearchSource.LANGSEARCH,
        endpoint = "https://api.langsearch.com/v1/search",
        resultKey = "results",
        queryParam = "q",
        keyHeader = "Authorization",
    )
}

/** Default HTTP transport for the connectors. */
object HttpTransport : JsonSearchConnector.Transport {
    override suspend fun get(url: String, headers: Map<String, String>): JsonSearchConnector.Response {
        return kotlinx.coroutines.Dispatchers.IO.runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            JsonSearchConnector.Response(code, body)
        }.getOrDefault(JsonSearchConnector.Response(-1, ""))
    }

    override suspend fun postJson(url: String, headers: Map<String, String>, body: String): JsonSearchConnector.Response {
        return kotlinx.coroutines.Dispatchers.IO.runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val out = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            JsonSearchConnector.Response(code, out)
        }.getOrDefault(JsonSearchConnector.Response(-1, ""))
    }
}
