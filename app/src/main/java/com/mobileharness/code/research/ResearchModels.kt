package com.mobileharness.code.research

/**
 * Web research models shared by the Sidekick research engine and the search connectors.
 */
enum class ResearchSource { MARGINALIA, LINKUP, JINA, LANGSEARCH, FIRECRAWL, DIRECT }

/** A single search hit. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String = "",
    val source: ResearchSource = ResearchSource.DIRECT,
)

/** Verdict of a claim check. */
enum class ClaimVerdict { VERIFIED, UNVERIFIED, DISPUTED, UNKNOWN }

/** A claim and the evidence supporting/contradicting it. */
data class ClaimAssessment(
    val claim: String,
    val verdict: ClaimVerdict,
    val supportingSources: List<String> = emptyList(),
    val contradictingSources: List<String> = emptyList(),
    val reason: String = "",
)
