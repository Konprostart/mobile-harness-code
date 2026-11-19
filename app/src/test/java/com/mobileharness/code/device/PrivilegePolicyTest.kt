package com.mobileharness.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegePolicyTest {

    private fun state(
        route: PrivilegeRoute = PrivilegeRoute.ROOT,
        available: Boolean = true,
        authorized: Boolean = true,
        level: PrivilegeLevel = PrivilegeLevel.ROOT,
    ) = PrivilegeRouteState(route, available, authorized, level)

    @Test
    fun denyWhenNotOptedIn() {
        val decision = PrivilegePolicy.decide(
            PrivilegeRequest(PrivilegeRoute.ROOT, PrivilegeLevel.ROOT, command = "id"),
            state(),
            optIn = false,
        )
        assertEquals(
            PrivilegePolicy.Decision.Denied(PrivilegePolicy.DenialReason.ROUTE_NOT_OPTED_IN),
            decision,
        )
    }

    @Test
    fun denyWhenUnavailable() {
        val decision = PrivilegePolicy.decide(
            PrivilegeRequest(PrivilegeRoute.ROOT, PrivilegeLevel.ROOT, command = "id"),
            state(available = false),
            optIn = true,
        )
        assertEquals(
            PrivilegePolicy.Decision.Denied(PrivilegePolicy.DenialReason.ROUTE_UNAVAILABLE),
            decision,
        )
    }

    @Test
    fun denyWhenNotAuthorized() {
        val decision = PrivilegePolicy.decide(
            PrivilegeRequest(PrivilegeRoute.SHIZUKU, PrivilegeLevel.SHELL, argv = listOf("id")),
            state(route = PrivilegeRoute.SHIZUKU, level = PrivilegeLevel.SHELL, authorized = false),
            optIn = true,
        )
        assertEquals(
            PrivilegePolicy.Decision.Denied(PrivilegePolicy.DenialReason.ROUTE_NOT_AUTHORIZED),
            decision,
        )
    }

    @Test
    fun denyOnLevelMismatch() {
        val decision = PrivilegePolicy.decide(
            PrivilegeRequest(PrivilegeRoute.SHIZUKU, PrivilegeLevel.ROOT, argv = listOf("id")),
            state(route = PrivilegeRoute.SHIZUKU, level = PrivilegeLevel.SHELL),
            optIn = true,
        )
        assertEquals(
            PrivilegePolicy.Decision.Denied(PrivilegePolicy.DenialReason.LEVEL_MISMATCH),
            decision,
        )
    }

    @Test
    fun denyOnInvalidPayload() {
        val decision = PrivilegePolicy.decide(
            PrivilegeRequest(PrivilegeRoute.ROOT, PrivilegeLevel.ROOT, command = ""),
            state(),
            optIn = true,
        )
        assertEquals(
            PrivilegePolicy.Decision.Denied(PrivilegePolicy.DenialReason.INVALID_PAYLOAD),
            decision,
        )
    }

    @Test
    fun allowValidRequestWithNormalLimits() {
        val decision = PrivilegePolicy.decide(
            PrivilegeRequest(PrivilegeRoute.ROOT, PrivilegeLevel.ROOT, command = "id"),
            state(),
            optIn = true,
        )
        val allowed = decision as PrivilegePolicy.Decision.Allowed
        assertEquals(PrivilegePolicy.TIMEOUT_MILLIS, allowed.timeoutMillis)
        assertEquals(PrivilegePolicy.MAX_OUTPUT_BYTES, allowed.maxOutputBytes)
    }

    @Test
    fun allowValidRequestWithEcoLimits() {
        val decision = PrivilegePolicy.decide(
            PrivilegeRequest(PrivilegeRoute.ROOT, PrivilegeLevel.ROOT, command = "id", ecoEnabled = true),
            state(),
            optIn = true,
        )
        val allowed = decision as PrivilegePolicy.Decision.Allowed
        assertEquals(PrivilegePolicy.TIMEOUT_MILLIS_ECO, allowed.timeoutMillis)
        assertEquals(PrivilegePolicy.MAX_OUTPUT_BYTES_ECO, allowed.maxOutputBytes)
    }

    @Test
    fun ecoLimitsAreStrictlySmaller() {
        assertTrue(PrivilegePolicy.TIMEOUT_MILLIS_ECO < PrivilegePolicy.TIMEOUT_MILLIS)
        assertTrue(PrivilegePolicy.MAX_OUTPUT_BYTES_ECO < PrivilegePolicy.MAX_OUTPUT_BYTES)
    }
}
