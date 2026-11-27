package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeSessionStoreTest {

    private fun store(): RuntimeSessionStore =
        RuntimeSessionStore(Files.createTempDirectory("runtime-sessions-test").toFile())

    private fun running(
        store: RuntimeSessionStore,
        sessionId: String = "session-1",
        projectId: String = "project-1",
        prompt: String = "Fix the build",
    ): RuntimeSessionRecord {
        store.recordRunning(
            sessionId = sessionId,
            projectId = projectId,
            agentStableId = "deepseek-harness",
            agentTitle = "DeepSeek Harness",
            model = "deepseek-v4-pro",
            providerId = "deepseek",
            prompt = prompt,
            historyMessageCount = 3,
            nowMillis = 1_000L,
        )
        return store.read(sessionId)!!
    }

    @Test
    fun runningRecordRoundTrips() {
        val store = store()
        val record = running(store)
        assertEquals("session-1", record.sessionId)
        assertEquals("project-1", record.projectId)
        assertEquals("deepseek-harness", record.agentStableId)
        assertEquals("deepseek-v4-pro", record.model)
        assertEquals(RuntimeSessionState.RUNNING, record.state)
        assertFalse(record.toolsExecuted)
        assertNull(record.exitCode)
    }

    @Test
    fun updateRunningMarksToolsWithoutClobberingOutput() {
        val store = store()
        running(store)
        store.updateRunning("session-1", lastOutput = "partial output", toolsExecuted = true)
        val record = store.read("session-1")!!
        assertTrue(record.toolsExecuted)
        assertEquals("partial output", record.lastOutput)
        assertEquals(RuntimeSessionState.RUNNING, record.state)
    }

    @Test
    fun mutatingToolsAreTrackedSeparately() {
        val store = store()
        running(store)
        store.updateRunning("session-1", mutatingToolsExecuted = true, toolsExecuted = true)
        val record = store.read("session-1")!!
        assertTrue(record.toolsExecuted)
        assertTrue(record.mutatingToolsExecuted)
    }

    @Test
    fun terminalTransitionIsAppliedOnce() {
        val store = store()
        running(store)
        store.recordTerminal("session-1", RuntimeSessionState.FAILED, exitCode = 137, lastError = "OOM")
        val record = store.read("session-1")!!
        assertEquals(RuntimeSessionState.FAILED, record.state)
        assertEquals(137, record.exitCode)
        assertEquals("OOM", record.lastError)
        // A later terminal update must not resurrect a finished session back into RUNNING.
        store.recordTerminal("session-1", RuntimeSessionState.COMPLETED, exitCode = 0)
        assertEquals(RuntimeSessionState.FAILED, store.read("session-1")!!.state)
    }

    @Test
    fun activeSessionReturnsLatestRunning() {
        val store = store()
        running(store, sessionId = "a", projectId = "p1")
        store.updateRunning("a", lastOutput = "older", nowMillis = 2_000L)
        running(store, sessionId = "b", projectId = "p2")
        store.updateRunning("b", lastOutput = "newer", nowMillis = 3_000L)
        assertEquals("b", store.activeSession()?.sessionId)
        assertEquals("b", store.activeSessions().first().sessionId)
        assertEquals(2, store.activeSessions().size)
    }

    @Test
    fun markAllInterruptedConvertsRunningOnly() {
        val store = store()
        running(store, sessionId = "a")
        running(store, sessionId = "b")
        store.recordTerminal("b", RuntimeSessionState.COMPLETED, exitCode = 0)
        val interrupted = store.markAllInterrupted(nowMillis = 9_000L)
        assertEquals(listOf("a"), interrupted.map { it.sessionId })
        assertEquals(RuntimeSessionState.INTERRUPTED, store.read("a")!!.state)
        assertEquals(RuntimeSessionState.COMPLETED, store.read("b")!!.state)
        assertTrue(store.activeSessions().isEmpty())
    }

    @Test
    fun promptAndErrorAreBounded() {
        val store = store()
        running(store, prompt = "x".repeat(10_000))
        store.recordTerminal("session-1", RuntimeSessionState.FAILED, 1, "e".repeat(10_000))
        val record = store.read("session-1")!!
        assertTrue(record.prompt.length <= 4_100)
        assertTrue(record.lastError.length <= 2_100)
    }

    @Test
    fun clearProjectRemovesOnlyThatProject() {
        val store = store()
        running(store, sessionId = "a", projectId = "p1")
        running(store, sessionId = "b", projectId = "p2")
        store.clearProject("p1")
        assertNull(store.read("a"))
        assertEquals("b", store.read("b")?.sessionId)
    }

    @Test
    fun recentExcludesRunningSessions() {
        val store = store()
        running(store, sessionId = "running")
        running(store, sessionId = "done")
        store.recordTerminal("done", RuntimeSessionState.COMPLETED, 0)
        val recent = store.recent()
        assertEquals(listOf("done"), recent.map { it.sessionId })
    }

    @Test
    fun terminalWithoutToolUpdateKeepsFailClosedFlags() {
        val store = store()
        running(store)
        store.recordTerminal("session-1", RuntimeSessionState.FAILED, exitCode = 137)
        val record = store.read("session-1")!!
        assertFalse(record.toolsExecuted)
        assertFalse(record.mutatingToolsExecuted)
    }

    @Test
    fun markAllInterruptedIsIdempotent() {
        val store = store()
        running(store, sessionId = "a")
        store.markAllInterrupted(nowMillis = 5_000L)
        // Second pass: nothing is RUNNING anymore, so it returns empty and changes nothing.
        assertTrue(store.markAllInterrupted(nowMillis = 6_000L).isEmpty())
        assertEquals(RuntimeSessionState.INTERRUPTED, store.read("a")!!.state)
    }

    @Test
    fun clearRemovesEverything() {
        val store = store()
        running(store, sessionId = "a")
        store.recordTerminal("a", RuntimeSessionState.COMPLETED, 0)
        store.clear()
        assertTrue(store.recent().isEmpty())
        assertNull(store.activeSession())
    }
}
