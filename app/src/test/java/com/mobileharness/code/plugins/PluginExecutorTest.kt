package com.mobileharness.code.plugins

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginExecutorTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun buildCommandResolvesInterpreter() {
        val tool = PluginTool("t", command = "scripts/t.py")
        assertEquals(listOf("python3", "scripts/t.py", "{}"), PluginExecutor.buildCommand(tool, "{}"))
    }

    @Test
    fun buildCommandSplitsBareCommand() {
        val tool = PluginTool("t", command = "echo hello")
        assertEquals(listOf("echo", "hello", "{}"), PluginExecutor.buildCommand(tool, "{}"))
    }

    @Test
    fun buildCommandEmptyForBlankCommand() {
        assertTrue(PluginExecutor.buildCommand(PluginTool("t", command = ""), "{}").isEmpty())
    }

    @Test
    fun requirePermissionIsExecForCommands() {
        assertEquals(PluginPermission.EXEC, PluginExecutor.requirePermission(PluginTool("t", command = "ls")))
        assertNull(PluginExecutor.requirePermission(PluginTool("t", command = "")))
    }

    @Test
    fun splitCommandNoShell() {
        assertEquals(listOf("echo", "a", "b"), PluginExecutor.splitCommand("echo a b"))
        assertEquals(emptyList<String>(), PluginExecutor.splitCommand("   "))
    }

    @Test
    fun runCapturesStdout() {
        val workDir = tmp.newFolder("work")
        val result = PluginExecutor.run(listOf("sh", "-c", "echo hello"), workDir, 5000)
        assertTrue(result.stdout.contains("hello"))
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun runEmptyCommandReturnsFailure() {
        val result = PluginExecutor.run(emptyList(), tmp.newFolder("w"), 1000)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun callBudgetLimitsCalls() {
        val budget = PluginExecutor.CallBudget(2)
        assertTrue(budget.consume())
        assertTrue(budget.consume())
        assertFalse(budget.consume())
        assertTrue(budget.exhausted)
    }
}
