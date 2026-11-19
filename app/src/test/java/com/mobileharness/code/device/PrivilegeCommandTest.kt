package com.mobileharness.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeCommandTest {
    @Test
    fun sanitizeCommand_rejectsBlank() {
        assertNull(PrivilegeCommand.sanitizeCommand(""))
        assertNull(PrivilegeCommand.sanitizeCommand("   "))
    }

    @Test
    fun sanitizeCommand_rejectsTooLong() {
        val long = "a".repeat(PrivilegeCommand.MAX_COMMAND_CHARS + 1)
        assertNull(PrivilegeCommand.sanitizeCommand(long))
    }

    @Test
    fun sanitizeCommand_rejectsNulAndControlChars() {
        assertNull(PrivilegeCommand.sanitizeCommand("echo \u0000"))
        assertNull(PrivilegeCommand.sanitizeCommand("echo \u0001"))
        assertNull(PrivilegeCommand.sanitizeCommand("echo \u001b"))
    }

    @Test
    fun sanitizeCommand_allowsTabAndNewline() {
        assertEquals("echo a\tb\nc", PrivilegeCommand.sanitizeCommand("  echo a\tb\nc  "))
    }

    @Test
    fun sanitizeCommand_trimsAndPassesNormalCommand() {
        assertEquals("id", PrivilegeCommand.sanitizeCommand("  id  "))
    }

    @Test
    fun sanitizeArgv_rejectsEmptyList() {
        assertNull(PrivilegeCommand.sanitizeArgv(emptyList()))
    }

    @Test
    fun sanitizeArgv_rejectsTooManyArgs() {
        val many = List(PrivilegeCommand.MAX_ARG_COUNT + 1) { "a" }
        assertNull(PrivilegeCommand.sanitizeArgv(many))
    }

    @Test
    fun sanitizeArgv_rejectsEmptyEntry() {
        assertNull(PrivilegeCommand.sanitizeArgv(listOf("id", "")))
    }

    @Test
    fun sanitizeArgv_rejectsControlCharsAndNul() {
        assertNull(PrivilegeCommand.sanitizeArgv(listOf("id", "\u0000")))
        assertNull(PrivilegeCommand.sanitizeArgv(listOf("id", "a\nb")))
    }

    @Test
    fun sanitizeArgv_acceptsValidList() {
        assertEquals(listOf("id", "-u"), PrivilegeCommand.sanitizeArgv(listOf("id", "-u")))
    }

    @Test
    fun validatePayload_usesCommandForRootAndArgvForOthers() {
        assertTrue(PrivilegeCommand.validatePayload(PrivilegeRequest(PrivilegeRoute.ROOT, PrivilegeLevel.ROOT, command = "id")))
        assertTrue(
            PrivilegeCommand.validatePayload(
                PrivilegeRequest(PrivilegeRoute.SHIZUKU, PrivilegeLevel.SHELL, argv = listOf("id")),
            ),
        )
    }
}
