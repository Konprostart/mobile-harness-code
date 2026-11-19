package com.mobileharness.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeEventLogTest {

    @Test
    fun recordAndSnapshotPreserveOrder() {
        PrivilegeEventLog.clear()
        PrivilegeEventLog.record(PrivilegeRoute.ROOT, PrivilegeStage.DETECTED, "a")
        PrivilegeEventLog.record(PrivilegeRoute.SHIZUKU, PrivilegeStage.EXEC, null)
        val snapshot = PrivilegeEventLog.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(PrivilegeRoute.ROOT, snapshot[0].route)
        assertEquals(PrivilegeRoute.SHIZUKU, snapshot[1].route)
    }

    @Test
    fun ringBufferCapsAtMaxEntries() {
        PrivilegeEventLog.clear()
        repeat(150) { i ->
            PrivilegeEventLog.record(PrivilegeRoute.ROOT, PrivilegeStage.DETECTED, "entry-$i")
        }
        val snapshot = PrivilegeEventLog.snapshot()
        assertEquals(100, snapshot.size)
        // The oldest entries were evicted, so the newest is last.
        assertEquals("entry-149", snapshot.last().detail)
        assertTrue(snapshot.first().detail != "entry-0")
    }

    @Test
    fun clearEmptiesBuffer() {
        PrivilegeEventLog.record(PrivilegeRoute.ROOT, PrivilegeStage.SUCCESS, null)
        PrivilegeEventLog.clear()
        assertTrue(PrivilegeEventLog.snapshot().isEmpty())
    }
}
