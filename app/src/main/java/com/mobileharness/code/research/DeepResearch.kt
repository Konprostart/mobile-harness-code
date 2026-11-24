package com.mobileharness.code.research

/**
 * Research depth levels. The numbers are *maximum goals*, not mandatory quotas: a run stops as soon
 * as enough sources are gathered or the cycle budget is exhausted, and never fabricates results to
 * meet a target.
 */
enum class ResearchDepth(val maxSources: Int, val maxCycles: Int, val label: String) {
    QUICK(5, 1, "Quick"),
    STANDARD(10, 2, "Standard"),
    DEEP(20, 3, "Deep"),
    EXTREME(50, 5, "Extreme"),
}

/** Confidence classification of a whole research report. */
enum class ResearchConfidence { CONFIRMED, MIXED, UNCONFIRMED }

/** A single source that was actually read/collected, ranked by relevance. */
data class RankedSource(val result: SearchResult, val relevance: Double)

/** A structured deep-research report. */
data class DeepResearchReport(
    val query: String,
    val depth: ResearchDepth,
    val sources: List<RankedSource>,
    val claims: List<ClaimAssessment>,
    val conflictingTopics: List<String>,
    val confidence: ResearchConfidence,
    val cyclesRun: Int,
) {
    /** Human-readable structured report, with source citations. */
    fun render(): String = buildString {
        append("Deep research: ").append(query).append('\n')
        append("Depth: ").append(depth.label).append(" (").append(depth.maxSources).append(" sources max)\n\n")
        append("Sources (").append(sources.size).append("):\n")
        sources.forEachIndexed { i, s ->
            append("  [").append(i + 1).append("] ").append(s.result.title).append('\n')
            append("      ").append(s.result.url).append('\n')
        }
        if (claims.isNotEmpty()) {
            append("\nClaims:\n")
            claims.forEach { c ->
                append("  - ").append(c.verdict).append(": ").append(c.claim)
                if (c.supportingSources.isNotEmpty()) append("  [supported by ").append(c.supportingSources.size).append(" source(s)]")
                if (c.contradictingSources.isNotEmpty()) append("  [contradicted by ").append(c.contradictingSources.size).append(" source(s)]")
                append('\n')
            }
        }
        if (conflictingTopics.isNotEmpty()) {
            append("\nConflicting topics:\n")
            conflictingTopics.forEach { append("  - ").append(it).append('\n') }
        }
        append("\nOverall confidence: ").append(confidence.name.lowercase())
    }
}

/**
 * Deep research orchestration. It is deliberately LLM-free and deterministic: follow-up queries are
 * derived from the query terms and top result titles (no model hallucination), sources are
 * deduplicated and ranked by relevance, and claims are checked with [ClaimCheck] against the
 * actually-collected evidence. "Confirmed" only means the evidence overlaps; it is never a
 * guarantee of truth.
 */
class DeepResearchEngine(
    private val search: suspend (String) -> List<SearchResult>,
    private val dedup: (List<SearchResult>) -> List<SearchResult> = { ResearchPolicy.trim(it, Int.MAX_VALUE) },
) {
    suspend fun research(query: String, depth: ResearchDepth): DeepResearchReport {
        val collected = LinkedHashMap<String, SearchResult>()
        val queries = ArrayDeque<String>()
        queries.addLast(query)
        var cycles = 0

        while (cycles < depth.maxCycles && queries.isNotEmpty() && collected.size < depth.maxSources) {
            val q = queries.removeFirst()
            val results = dedup(search(q))
            for (result in results) {
                collected.putIfAbsent(result.url.trim().trimEnd('/'), result)
                if (collected.size >= depth.maxSources) break
            }
            if (results.isNotEmpty() && cycles < depth.maxCycles - 1) {
                deriveFollowUps(query, results).forEach { followUp ->
                    if (queries.size < depth.maxSources) queries.addLast(followUp)
                }
            }
            cycles++
        }

        val sources = collected.values.toList()
        val ranked = sources
            .map { RankedSource(it, TextRanking.relevance(query, it)) }
            .sortedByDescending { it.relevance }
            .take(depth.maxSources)

        val claims = extractAndAssess(query, sources)
        val conflicting = claims.filter { it.verdict == ClaimVerdict.DISPUTED }
            .map { it.claim.take(120) }
            .distinct()
            .take(10)

        val confidence = when {
            conflicting.isNotEmpty() -> ResearchConfidence.MIXED
            claims.any { it.verdict == ClaimVerdict.VERIFIED } -> ResearchConfidence.CONFIRMED
            sources.isEmpty() -> ResearchConfidence.UNCONFIRMED
            else -> ResearchConfidence.UNCONFIRMED
        }

        return DeepResearchReport(query, depth, ranked, claims, conflicting, confidence, cycles)
    }

    /** Derives follow-up queries from the most distinctive query terms + top result titles. */
    fun deriveFollowUps(query: String, results: List<SearchResult>): List<String> {
        val base = TextRanking.terms(query).take(4)
        if (base.isEmpty()) return emptyList()
        val followUps = LinkedHashSet<String>()
        for (result in results.take(5)) {
            val titleTerms = TextRanking.terms(result.title).filterNot { it in base }.take(3)
            if (titleTerms.isNotEmpty()) {
                followUps += (base + titleTerms).joinToString(" ")
            }
        }
        // Also a plain "more about X" query for the primary term.
        if (results.size >= 3) followUps += "details ${base.joinToString(" ")}"
        return followUps.take(6).filter { it != query }
    }

    private fun extractAndAssess(query: String, sources: List<SearchResult>): List<ClaimAssessment> {
        if (sources.isEmpty()) return emptyList()
        // Candidate claims come from the query itself plus top snippet sentences.
        val candidates = LinkedHashSet<String>()
        ClaimCheck.extractClaims(query).forEach { candidates.add(it) }
        for (result in sources.take(6)) {
            ClaimCheck.extractClaims(result.snippet).take(3).forEach { candidates.add(it) }
        }
        return candidates.take(12).map { ClaimCheck.assess(it, sources) }
    }
}
