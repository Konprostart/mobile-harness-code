package com.mobileharness.code.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAuditLogTest {

    @Test
    fun `records and bounds entries`() {
        val log = PermissionAuditLog(maxEntries = 3)
        repeat(5) { i ->
            log.record(
                PermissionRequest(PermissionSubject.TOOL, "Bash", operation = "op$i"),
                PermissionDecision.Allowed("r"),
            )
        }
        assertEquals(3, log.snapshot().size)
        assertEquals("op2", log.snapshot().first().operation)
    }

    @Test
    fun `redacts api keys and bearer tokens`() {
        val log = PermissionAuditLog()
        log.record(
            PermissionRequest(PermissionSubject.TOOL, "Bash", operation = "key=sk-abcdef1234567890"),
            PermissionDecision.NeedsApproval("reason"),
        )
        val entry = log.snapshot().single()
        assertFalse(entry.operation!!.contains("sk-abcdef1234567890"))
        assertTrue(entry.operation!!.contains("[REDACTED]"))
    }

    @Test
    fun `json export and parse`() {
        val log = PermissionAuditLog()
        log.record(
            PermissionRequest(PermissionSubject.TOOL, "Edit", operation = "edit file"),
            PermissionDecision.Denied(DenialReason.EXPLICIT_DENY),
        )
        val json = log.toJson()
        assertTrue(json.contains("EXPLICIT_DENY"))
        assertTrue(json.contains("Edit"))
    }
}
