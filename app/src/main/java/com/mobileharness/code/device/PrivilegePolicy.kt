package com.mobileharness.code.device

/**
 * Pure, fail-closed authorization policy for privileged execution.
 *
 * Every command flows through [decide] before it is run. The order of checks matters and is
 * deliberately pessimistic: any unmet condition denies the request with a concrete reason, so a
 * missing opt-in, a dead transport, a missing Shizuku grant or an invalid payload can never
 * silently fall back to a weaker (or unprivileged) execution path.
 */
object PrivilegePolicy {
    /** Maximum output (stdout + stderr) bytes returned to the UI in normal mode. */
    const val MAX_OUTPUT_BYTES = 256 * 1024

    /** Reduced output budget in Eco Mode. */
    const val MAX_OUTPUT_BYTES_ECO = 64 * 1024

    /** Execution timeout in normal mode. */
    const val TIMEOUT_MILLIS = 60_000L

    /** Reduced execution timeout in Eco Mode. */
    const val TIMEOUT_MILLIS_ECO = 30_000L

    sealed interface Decision {
        data class Allowed(
            val request: PrivilegeRequest,
            val timeoutMillis: Long,
            val maxOutputBytes: Int,
        ) : Decision

        data class Denied(val reason: DenialReason) : Decision
    }

    enum class DenialReason {
        /** The route was not explicitly enabled by the user. */
        ROUTE_NOT_OPTED_IN,

        /** The transport is not currently present. */
        ROUTE_UNAVAILABLE,

        /** The transport is present but this app has not been granted access. */
        ROUTE_NOT_AUTHORIZED,

        /** The request asks for more privilege than the route can provide. */
        LEVEL_MISMATCH,

        /** The command or argv payload failed sanitization. */
        INVALID_PAYLOAD,
    }

    fun maxOutputBytes(ecoEnabled: Boolean): Int =
        if (ecoEnabled) MAX_OUTPUT_BYTES_ECO else MAX_OUTPUT_BYTES

    fun timeoutMillis(ecoEnabled: Boolean): Long =
        if (ecoEnabled) TIMEOUT_MILLIS_ECO else TIMEOUT_MILLIS

    /**
     * Fail-closed gate for [request] against a detected [state] and the user's per-route [optIn].
     */
    fun decide(request: PrivilegeRequest, state: PrivilegeRouteState, optIn: Boolean): Decision {
        if (!optIn) return Decision.Denied(DenialReason.ROUTE_NOT_OPTED_IN)
        if (!state.available) return Decision.Denied(DenialReason.ROUTE_UNAVAILABLE)
        if (!state.authorized) return Decision.Denied(DenialReason.ROUTE_NOT_AUTHORIZED)
        if (request.level.rank > state.level.rank) return Decision.Denied(DenialReason.LEVEL_MISMATCH)
        if (!PrivilegeCommand.validatePayload(request)) return Decision.Denied(DenialReason.INVALID_PAYLOAD)
        return Decision.Allowed(
            request = request,
            timeoutMillis = timeoutMillis(request.ecoEnabled),
            maxOutputBytes = maxOutputBytes(request.ecoEnabled),
        )
    }
}
