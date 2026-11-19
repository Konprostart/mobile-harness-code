package com.mobileharness.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeRouteStateTest {

    @Test
    fun fromStableId_roundTripsAllRoutes() {
        for (route in PrivilegeRoute.entries) {
            assertEquals(route, PrivilegeRoute.fromStableId(route.stableId))
        }
    }

    @Test
    fun fromStableId_returnsNullForUnknown() {
        assertEquals(null, PrivilegeRoute.fromStableId("does-not-exist"))
    }

    @Test
    fun usableRequiresAvailableAuthorizedAndLevel() {
        assertFalse(PrivilegeRouteState(PrivilegeRoute.ROOT, false, true, PrivilegeLevel.ROOT).usable)
        assertFalse(PrivilegeRouteState(PrivilegeRoute.ROOT, true, false, PrivilegeLevel.ROOT).usable)
        assertFalse(PrivilegeRouteState(PrivilegeRoute.ROOT, true, true, PrivilegeLevel.NONE).usable)
        assertTrue(PrivilegeRouteState(PrivilegeRoute.ROOT, true, true, PrivilegeLevel.ROOT).usable)
    }

    @Test
    fun levelRanksAreOrdered() {
        assertTrue(PrivilegeLevel.NONE.rank < PrivilegeLevel.SHELL.rank)
        assertTrue(PrivilegeLevel.SHELL.rank < PrivilegeLevel.ROOT.rank)
    }
}
