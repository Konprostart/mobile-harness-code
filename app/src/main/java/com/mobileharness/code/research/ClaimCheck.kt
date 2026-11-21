package com.mobileharness.code.research

import org.json.JSONArray
import org.json.JSONObject

/**
 * Claim verification: extracts candidate claims from an assistant answer and scores each against
 * the search results gathered by the connectors. Pure and deterministic so it can be unit-tested
 * with fixtures. A claim is VERIFIED when at least one source supports it and none contradicts it;
 * DISPUTED when sources conflict; UNVERIFIED when nothing supports it; UNKNOWN when there is no
 * evidence at all.
 */
object ClaimCheck {
    /** Sentence-splits text and returns trimmed, non-empty candidate claims. */
    fun extractClaims(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim().trimEnd('.', '!', '?', ';', ',') }
            .filter { claim ->
                claim.length >= 12 && claim.split(' ').size >= 3
            }
    }

    fun assess(claim: String, results: List<SearchResult>): ClaimAssessment {
        if (results.isEmpty()) {
            return ClaimAssessment(claim, ClaimVerdict.UNKNOWN, reason = "No search results available.")
        }
        val tokens = tokenize(claim)
        if (tokens.size < 3) {
            return ClaimAssessment(claim, ClaimVerdict.UNKNOWN, reason = "Claim is too short to verify.")
        }
        val supporting = mutableListOf<String>()
        val contradicting = mutableListOf<String>()
        for (result in results) {
            val haystack = "${result.title} ${result.snippet}".lowercase()
            val matched = tokens.count { haystack.contains(it) }
            val coverage = matched.toDouble() / tokens.size
            when {
                coverage >= 0.5 -> supporting += result.url
                coverage in 0.25..0.49 -> contradicting += result.url
            }
        }
        return when {
            supporting.isNotEmpty() && contradicting.isEmpty() ->
                ClaimAssessment(claim, ClaimVerdict.VERIFIED, supportingSources = supporting.distinct())
            supporting.isNotEmpty() && contradicting.isNotEmpty() ->
                ClaimAssessment(
                    claim,
                    ClaimVerdict.DISPUTED,
                    supportingSources = supporting.distinct(),
                    contradictingSources = contradicting.distinct(),
                    reason = "Sources both support and conflict with this claim.",
                )
            contradicting.isNotEmpty() ->
                ClaimAssessment(claim, ClaimVerdict.UNVERIFIED, contradictingSources = contradicting.distinct(), reason = "No source clearly supports this claim.")
            else -> ClaimAssessment(claim, ClaimVerdict.UNKNOWN, reason = "Insufficient overlap with any source.")
        }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .distinct()

    /** Parses a JSON list of search results from any connector into the common model. */
    fun parseResults(json: String, source: ResearchSource): List<SearchResult> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResult>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val url = obj.optString("url").ifBlank { obj.optString("link") }
            if (url.isBlank()) continue
            results += SearchResult(
                title = obj.optString("title").ifBlank { url },
                url = url,
                snippet = obj.optString("snippet").ifBlank { obj.optString("description").orEmpty() },
                source = source,
            )
        }
        return results
    }

    /** Loose extraction from a JSON body whose results live under a named array property. */
    fun parseResultsNested(json: String, key: String, source: ResearchSource): List<SearchResult> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val array = obj.optJSONArray(key) ?: return emptyList()
        val sb = StringBuilder("[")
        for (i in 0 until array.length()) sb.append(array.opt(i).toString()).append(if (i == array.length() - 1) "" else ",")
        sb.append("]")
        return parseResults(sb.toString(), source)
    }
}
