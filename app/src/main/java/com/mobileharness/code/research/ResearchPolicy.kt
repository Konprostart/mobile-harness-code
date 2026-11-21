package com.mobileharness.code.research

import com.mobileharness.code.network.LocalNetwork
import java.net.URI

/**
 * Pure, fail-closed policy for web research: URL confinement (SSRF guard), result/request budgets
 * and a simple rate-limit so a research run cannot hammer a public service or walk into private
 * network space.
 */
object ResearchPolicy {
    /** Maximum results returned per query in normal mode. */
    const val MAX_RESULTS = 12

    const val MAX_RESULTS_ECO = 5

    /** Maximum outbound fetch depth (number of "dig deeper" hops). */
    const val MAX_DEPTH = 3

    /** Minimum interval between connector calls within one research run. */
    const val MIN_INTERVAL_MILLIS = 500L

    /** HTTPS always allowed; plain HTTP only for loopback/private/link-local hosts. */
    fun isAllowedUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host ?: return false
        return when (scheme) {
            "https" -> host.isNotBlank() && !LocalNetwork.isLocalHost(host)
            "http" -> LocalNetwork.isLocalHost(host)
            else -> false
        }
    }

    fun maxResults(ecoEnabled: Boolean): Int = if (ecoEnabled) MAX_RESULTS_ECO else MAX_RESULTS

    /** Deduplicates and trims raw results to the budget, keeping order. */
    fun trim(results: List<SearchResult>, maxResults: Int): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            val key = result.url.trim().trimEnd('/')
            seen.add(key)
        }.take(maxResults)
    }

    /** Simple token-bucket-style gate expressed as a minimum spacing between calls. */
    fun backoffMillis(ecoEnabled: Boolean): Long = if (ecoEnabled) MIN_INTERVAL_MILLIS * 2 else MIN_INTERVAL_MILLIS
}
