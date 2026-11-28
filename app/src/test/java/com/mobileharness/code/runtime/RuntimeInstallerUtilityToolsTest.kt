package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optional "Utility Tools" stack is installed from Ubuntu 20.04 apt packages
 * that are all permissively licensed. These tests pin the exact package/binary set
 * so a future edit cannot silently pull in a copyleft (e.g. GPL) tool.
 */
class RuntimeInstallerUtilityToolsTest {

    @Test
    fun utilityToolPackagesArePermissiveAndExpected() {
        assertEquals(
            listOf("jq", "ripgrep", "fd-find", "zip", "unzip"),
            RuntimeInstaller.UTILITY_TOOL_PACKAGES,
        )
        // `tree` is GPL-2.0 and must never be installed by the utility stack.
        assertFalse("tree is GPL-2.0 and must be excluded", "tree" in RuntimeInstaller.UTILITY_TOOL_PACKAGES)
    }

    @Test
    fun utilityToolBinariesAlignWithPackages() {
        assertEquals(
            listOf("usr/bin/jq", "usr/bin/rg", "usr/bin/fdfind", "usr/bin/zip", "usr/bin/unzip"),
            RuntimeInstaller.UTILITY_TOOL_BINARIES,
        )
    }

    @Test
    fun verifyCommandChecksEveryTool() {
        val command = RuntimeInstaller.UTILITY_TOOL_VERIFY_COMMAND
        assertTrue("verifies jq", "jq --version" in command)
        assertTrue("verifies ripgrep", "rg --version" in command)
        assertTrue("verifies fd-find", "fdfind --version" in command)
        assertTrue("verifies zip", "zip" in command)
        assertTrue("verifies unzip", "unzip" in command)
    }
}
