package com.mobileharness.code.network.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshCommandTest {
    @Test
    fun sanitizeTrimsAndAcceptsNormalCommands() {
        assertEquals("git status", SshCommand.sanitize("  git status  "))
        assertEquals("ls -la", SshCommand.sanitize("ls -la"))
    }

    @Test
    fun sanitizeAllowsTabAndNewline() {
        assertEquals("echo a\tb\nc", SshCommand.sanitize("echo a\tb\nc"))
    }

    @Test
    fun sanitizeRejectsEmptyBlankAndControlCharacters() {
        assertNull(SshCommand.sanitize(""))
        assertNull(SshCommand.sanitize("   "))
        assertNull(SshCommand.sanitize("echo\u0007bell"))
        assertNull(SshCommand.sanitize("echo\u0000nul"))
    }

    @Test
    fun sanitizeRejectsOverlongCommand() {
        val long = "x".repeat(SshCommand.MAX_COMMAND_CHARS + 1)
        assertNull(SshCommand.sanitize(long))
    }

    @Test
    fun truncateKeepsShortTextAndAppendsEllipsis() {
        assertEquals("abc", SshCommand.truncate("abc", 5))
        assertEquals("ab…", SshCommand.truncate("abcdef", 3))
    }

    @Test
    fun truncateHandlesZeroBudget() {
        assertEquals("", SshCommand.truncate("abc", 0))
    }

    @Test
    fun ecoModeShrinksBudgetAndTimeout() {
        assertTrue(SshCommand.maxOutputChars(true) < SshCommand.maxOutputChars(false))
        assertTrue(SshCommand.timeoutMillis(true) < SshCommand.timeoutMillis(false))
    }
}
