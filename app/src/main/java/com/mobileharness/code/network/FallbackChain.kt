package com.mobileharness.code.network

import kotlinx.coroutines.delay

/**
 * Machine-readable reason a completion failed. Routing decides on this, never by string matching
 * provider error text, so transient overload can be retried on another model while an invalid key
 * is reported clearly and never cascades.
 */
enum class CompletionFailureKind {
    /** 401 / 403 — the key or account is rejected. Fail-closed by default (never fall back). */
    AUTH,

    /** 429 — rate limit. A good fallback trigger. */
    RATE_LIMITED,

    /** 402 / insufficient balance — the account has no credit left. Rotate provider, not model. */
    QUOTA_EXCEEDED,

    /** 500..599 — provider is temporarily down or overloaded. A good fallback trigger. */
    SERVER_ERROR,

    /** Network read/connect timeout. A good fallback trigger. */
    TIMEOUT,

    /** Other transport failure (DNS, connection refused, offline). */
    NETWORK,

    /** 400 / 404 / 422 — the request itself is wrong (unknown model, bad payload). Fail-closed. */
    BAD_REQUEST,

    /** Anything else that is not obviously safe to retry. */
    OTHER,
}

/**
 * Classifies a completion failure from its HTTP status and (for transport failures) the error
 * message. Pure and deterministic so the router's decision is fully unit-testable.
 */
fun classifyCompletionFailure(httpCode: Int, error: String?): CompletionFailureKind {
    val message = error.orEmpty()
    return when {
        httpCode == 401 || httpCode == 403 -> CompletionFailureKind.AUTH
        httpCode == 429 -> CompletionFailureKind.RATE_LIMITED
        httpCode == 402 -> CompletionFailureKind.QUOTA_EXCEEDED
        httpCode in 500..599 -> CompletionFailureKind.SERVER_ERROR
        httpCode == 400 || httpCode == 404 || httpCode == 422 -> CompletionFailureKind.BAD_REQUEST
        httpCode > 0 -> CompletionFailureKind.OTHER
        message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) -> CompletionFailureKind.TIMEOUT
        else -> CompletionFailureKind.NETWORK
    }
}

/**
 * Classifies a coding-agent failure from its user-facing reason string. The bridges surface only a
 * reason string at `SessionFailed` (no structured HTTP code), so this maps the stable English
 * phrases they emit onto [CompletionFailureKind] for the same routing decision as
 * [classifyCompletionFailure]. Deliberately conservative: unknown text is [CompletionFailureKind.OTHER].
 */
fun classifyFailureKindFromReason(reason: String): CompletionFailureKind {
    val value = reason.lowercase()
    val serverCode = Regex("\\b(500|502|503|504)\\b").containsMatchIn(value)
    return when {
        "user not found" in value || "api key" in value || "authentication" in value ||
            "http 401" in value || "http 403" in value -> CompletionFailureKind.AUTH
        "rate limit" in value || "429" in value -> CompletionFailureKind.RATE_LIMITED
        "quota" in value || "402" in value || "insufficient" in value -> CompletionFailureKind.QUOTA_EXCEEDED
        "server error" in value || "internal server" in value || "bad gateway" in value ||
            "service unavailable" in value || "upstream" in value || serverCode ->
            CompletionFailureKind.SERVER_ERROR
        "connection error" in value || "transport" in value || "network" in value -> CompletionFailureKind.NETWORK
        "timeout" in value || "timed out" in value -> CompletionFailureKind.TIMEOUT
        else -> CompletionFailureKind.OTHER
    }
}

/**
 * True when a coding-agent failure is a transient *provider rejection* that reconnect cannot fix
 * — rate limit (429), server error (5xx) or exhausted credit (402). Network/timeout dropouts are
 * deliberately excluded: those are handled by the transport-recovery path, which reconnects first.
 */
fun isRotatableProviderFailure(reason: String): Boolean {
    val kind = classifyFailureKindFromReason(reason)
    return kind == CompletionFailureKind.RATE_LIMITED ||
        kind == CompletionFailureKind.SERVER_ERROR ||
        kind == CompletionFailureKind.QUOTA_EXCEEDED
}

/** A single model step in a fallback chain. [id] is a stable label used only for logs. */
data class ModelRoute(val id: String, val model: String)

/** Outcome of one route attempt, before the chain annotates which route produced it. */
sealed interface RouteAttempt {
    data class Success(val text: String, val reasoning: String? = null) : RouteAttempt
    data class Failure(val kind: CompletionFailureKind, val message: String) : RouteAttempt
}

/** Final outcome of a fallback chain, annotated with the route that produced it. */
sealed interface RouteOutcome {
    data class Success(
        val text: String,
        val reasoning: String?,
        val routeId: String,
        val routeModel: String,
    ) : RouteOutcome

    data class Failure(
        val kind: CompletionFailureKind,
        val message: String,
        val routeId: String,
        val routeModel: String,
    ) : RouteOutcome
}

enum class RouteStage { ATTEMPT, FALLBACK, EXHAUSTED, BUDGET_HALTED }

/** Audit event emitted while a chain runs, surfaced in Developer Tools → Routing log. */
data class RouteEvent(
    val routeId: String,
    val model: String,
    val stage: RouteStage,
    val detail: String? = null,
)

/**
 * Tracks estimated token spend and request count for a routing session. Deterministic: state only
 * moves through [recordAttempt], so it is unit-testable without a clock or network.
 */
class RouteBudget(val maxRequests: Int, val maxTokens: Long) {
    var requestCount: Int = 0
        private set
    var estimatedTokens: Long = 0L
        private set

    val exhausted: Boolean get() = requestCount >= maxRequests || estimatedTokens >= maxTokens

    /** Records one attempt, estimating its token cost conservatively (the requested budget). */
    fun recordAttempt(estimatedTokensForRequest: Long) {
        requestCount++
        estimatedTokens = (estimatedTokens + estimatedTokensForRequest.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}

/**
 * Pure routing policy. Mutating operations never fall back (fail-closed: a side-effecting command
 * must not be re-sent to another model), and auth errors are reported instead of cascading unless
 * the caller explicitly opts into auth-fallback.
 */
object FallbackPolicy {
    fun shouldFallback(kind: CompletionFailureKind, mutating: Boolean, fallbackOnAuth: Boolean = false): Boolean {
        if (mutating) return false
        if (kind == CompletionFailureKind.AUTH) return fallbackOnAuth
        if (kind == CompletionFailureKind.BAD_REQUEST || kind == CompletionFailureKind.OTHER) return false
        return true
    }

    /** Small exponential cooldown between fallback attempts, capped so a chain never stalls long. */
    fun backoffDelayMillis(attemptIndex: Int, ecoEnabled: Boolean): Long {
        val base = if (ecoEnabled) 1_500L else 800L
        val shift = attemptIndex.coerceIn(0, 5)
        return (base shl shift).coerceAtMost(30_000L)
    }
}

/** Log entry kept in [RouteLog] and shown in Developer Tools. */
data class RouteLogEntry(
    val timestampMillis: Long,
    val routeId: String,
    val model: String,
    val stage: RouteStage,
    val detail: String? = null,
)

/**
 * In-memory ring buffer of routing events shared by the assistant engine and Developer Tools.
 * Process-scoped and cleared on restart; it never contains API keys, only route/model labels.
 */
object RouteLog {
    private const val MAX_ENTRIES = 100
    private val entries = ArrayDeque<RouteLogEntry>()

    @Synchronized
    fun record(entry: RouteLogEntry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<RouteLogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Runs an ordered list of [ModelRoute]s, trying the next one when the current fails with a
 * retryable (transient) error. Auth and bad-request errors stop the chain fail-closed, and
 * mutating chains never fall back at all. A [budget], when provided, halts the chain fail-closed
 * before any attempt would exceed it.
 */
class FallbackChain(
    private val routes: List<ModelRoute>,
    private val budget: RouteBudget? = null,
    private val mutating: Boolean = false,
    private val ecoEnabled: Boolean = false,
    private val fallbackOnAuth: Boolean = false,
    private val backoff: suspend (Long) -> Unit = { delay(it) },
    private val onEvent: (RouteEvent) -> Unit = {},
) {
    /**
     * Runs the chain. [complete] performs the actual provider call for a single route and returns
     * a [RouteAttempt] without route identity (the chain annotates it).
     */
    suspend fun run(complete: suspend (ModelRoute) -> RouteAttempt): RouteOutcome {
        if (routes.isEmpty()) {
            return RouteOutcome.Failure(CompletionFailureKind.OTHER, "No route configured", "", "")
        }
        var last: RouteOutcome? = null
        routes.forEachIndexed { index, route ->
            if (budget != null && budget.exhausted) {
                val detail = "budget exhausted (${budget.requestCount}/${budget.maxRequests} requests, " +
                    "${budget.estimatedTokens}/${budget.maxTokens} tokens)"
                onEvent(RouteEvent(route.id, route.model, RouteStage.BUDGET_HALTED, detail))
                return RouteOutcome.Failure(CompletionFailureKind.OTHER, detail, route.id, route.model)
            }
            if (index > 0) {
                onEvent(RouteEvent(route.id, route.model, RouteStage.FALLBACK, "trying fallback model"))
            }
            budget?.recordAttempt(tokensPerAttempt)
            onEvent(RouteEvent(route.id, route.model, RouteStage.ATTEMPT, "requesting completion"))
            when (val attempt = complete(route)) {
                is RouteAttempt.Success -> {
                    val outcome = RouteOutcome.Success(attempt.text, attempt.reasoning, route.id, route.model)
                    onEvent(RouteEvent(route.id, route.model, RouteStage.ATTEMPT, "success"))
                    return outcome
                }
                is RouteAttempt.Failure -> {
                    val outcome = RouteOutcome.Failure(attempt.kind, attempt.message, route.id, route.model)
                    last = outcome
                    if (!FallbackPolicy.shouldFallback(attempt.kind, mutating, fallbackOnAuth)) {
                        onEvent(RouteEvent(route.id, route.model, RouteStage.ATTEMPT, "fail-closed: ${attempt.kind}"))
                        return outcome
                    }
                    if (index < routes.lastIndex) {
                        val delayMs = FallbackPolicy.backoffDelayMillis(index, ecoEnabled)
                        onEvent(RouteEvent(route.id, route.model, RouteStage.FALLBACK, "retryable ${attempt.kind}, backing off ${delayMs}ms"))
                        backoff(delayMs)
                    }
                }
            }
        }
        val lastRouteId = when (last) {
            is RouteOutcome.Success -> last.routeId
            is RouteOutcome.Failure -> last.routeId
            null -> ""
        }
        val lastRouteModel = when (last) {
            is RouteOutcome.Success -> last.routeModel
            is RouteOutcome.Failure -> last.routeModel
            null -> ""
        }
        onEvent(RouteEvent(lastRouteId, lastRouteModel, RouteStage.EXHAUSTED, "all routes failed"))
        return last ?: RouteOutcome.Failure(CompletionFailureKind.OTHER, "No route configured", "", "")
    }

    /**
     * Conservative token estimate per attempt, injected so tests and the engine agree on what a
     * "request" costs against the budget. Defaults to one token (the budget then acts as a pure
     * request-count cap).
     */
    var tokensPerAttempt: Long = 1L
}
