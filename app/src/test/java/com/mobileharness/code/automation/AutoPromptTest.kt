package com.mobileharness.code.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPromptTest {

    private fun task(
        id: String = "t1",
        agentKind: String = "claude",
        projectId: String = "p1",
        prompt: String = "Run the tests",
        recurrence: Recurrence = Recurrence.ONE_SHOT,
        nextRunMillis: Long = 1_000L,
        intervalMillis: Long = 0L,
        runMode: RunMode = RunMode.SCHEDULED,
        description: String = "",
        providerId: String = "",
        model: String = "",
    ) = ScheduledTask(
        id = id,
        agentKind = agentKind,
        projectId = projectId,
        prompt = prompt,
        recurrence = recurrence,
        nextRunMillis = nextRunMillis,
        intervalMillis = intervalMillis,
        runMode = runMode,
        description = description,
        providerId = providerId,
        model = model,
    )

    // ── Codec ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `codec round trips the new fields`() {
        val original = listOf(
            task(
                id = "a",
                description = "Deep research prompt",
                providerId = "openai",
                model = "gpt-4o",
                runMode = RunMode.MANUAL,
            ).copy(lastRunMillis = 1234L, lastRunStatus = PromptRunStatus.SUCCESS.name),
        )
        val restored = ScheduledTaskCodec.decode(ScheduledTaskCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `codec decodes legacy json without new fields`() {
        val legacy = """[{"id":"t1","agentKind":"claude","projectId":"p1","prompt":"x","recurrence":"ONE_SHOT","nextRunMillis":1000,"intervalMillis":0,"enabled":true,"title":"Old"}]"""
        val tasks = ScheduledTaskCodec.decode(legacy)
        assertEquals(1, tasks.size)
        assertEquals("", tasks[0].description)
        assertEquals("", tasks[0].providerId)
        assertEquals("", tasks[0].model)
        assertEquals(RunMode.SCHEDULED, tasks[0].runMode)
        assertEquals(0L, tasks[0].lastRunMillis)
    }

    @Test
    fun `codec skips malformed entries`() {
        val bad = """[{"id":"t1","agentKind":"claude","projectId":"p1","prompt":"x","recurrence":"BOGUS","nextRunMillis":1},{"id":"t2"}]"""
        assertTrue(ScheduledTaskCodec.decode(bad).isEmpty())
    }

    // ── Policy ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `sidekick does not require a project`() {
        assertTrue(SchedulePolicy.isValid(task(agentKind = "sidekick", projectId = ""), ecoEnabled = false))
        assertFalse(SchedulePolicy.isValid(task(agentKind = "claude", projectId = ""), ecoEnabled = false))
    }

    @Test
    fun `description over the cap is rejected`() {
        val long = task(description = "x".repeat(SchedulePolicy.MAX_DESCRIPTION_CHARS + 1))
        assertFalse(SchedulePolicy.isValid(long, ecoEnabled = false))
        assertTrue(SchedulePolicy.isValid(task(description = "ok"), ecoEnabled = false))
    }

    // ── Parser ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `parser supports daily and weekly`() {
        val daily = ScheduleParser.parse("daily", nowMillis = 0L, ecoEnabled = false)!!
        assertEquals(Recurrence.INTERVAL, daily.recurrence)
        assertEquals(24 * 60 * 60_000L, daily.intervalMillis)

        val weekly = ScheduleParser.parse("weekly", nowMillis = 0L, ecoEnabled = false)!!
        assertEquals(7 * 24 * 60 * 60_000L, weekly.intervalMillis)
    }

    // ── Store ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `store reorders and duplicates`() {
        val store = SchedulerStore()
        store.upsert(task(id = "a"))
        store.upsert(task(id = "b"))
        store.upsert(task(id = "c"))
        assertEquals(listOf("a", "b", "c"), store.snapshot().map { it.id })
        store.reorder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), store.snapshot().map { it.id })

        val dup = store.duplicate("c")!!
        assertTrue(dup.id != "c")
        assertEquals(4, store.snapshot().size)
    }

    @Test
    fun `store touches last run`() {
        val store = SchedulerStore()
        store.upsert(task(id = "a"))
        assertTrue(store.touchLastRun("a", 5000L, PromptRunStatus.SUCCESS))
        assertEquals(5000L, store.get("a")!!.lastRunMillis)
        assertEquals(PromptRunStatus.SUCCESS.name, store.get("a")!!.lastRunStatus)
    }

    // ── History ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `history records updates and clears`() {
        val store = AutoPromptHistoryStore(maxRuns = 3)
        store.record(PromptRun("r1", "p1", 1L, status = PromptRunStatus.RUNNING, agentKind = "claude"))
        store.update("r1") { it.copy(status = PromptRunStatus.SUCCESS, finishedAtMillis = 2L) }
        val latest = store.latestFor("p1")!!
        assertEquals(PromptRunStatus.SUCCESS, latest.status)
        store.clearFor("p1")
        assertTrue(store.byPrompt("p1").isEmpty())
    }

    @Test
    fun `history is bounded`() {
        val store = AutoPromptHistoryStore(maxRuns = 3)
        for (i in 1..5) store.record(PromptRun("r$i", "p1", i.toLong(), status = PromptRunStatus.SUCCESS, agentKind = "claude"))
        assertEquals(3, store.snapshot().size)
    }

    // ── AutoPromptPolicy ───────────────────────────────────────────────────────────────────

    @Test
    fun `scheduler gate honors master per-prompt and run mode`() {
        assertTrue(AutoPromptPolicy.shouldSchedule(task(), masterEnabled = true, ecoEnabled = false, hourOfDay = 12))
        assertFalse(AutoPromptPolicy.shouldSchedule(task(), masterEnabled = false, ecoEnabled = false, hourOfDay = 12))
        assertFalse(AutoPromptPolicy.shouldSchedule(task().copy(enabled = false), masterEnabled = true, ecoEnabled = false, hourOfDay = 12))
        assertFalse(AutoPromptPolicy.shouldSchedule(task(runMode = RunMode.MANUAL), masterEnabled = true, ecoEnabled = false, hourOfDay = 12))
        assertFalse(AutoPromptPolicy.shouldSchedule(task(), masterEnabled = true, ecoEnabled = true, hourOfDay = 3))
    }

    @Test
    fun `manual run is always allowed unless already running`() {
        assertTrue(AutoPromptPolicy.mayRunManually(task(), alreadyRunning = false))
        assertFalse(AutoPromptPolicy.mayRunManually(task(), alreadyRunning = true))
        assertFalse(AutoPromptPolicy.mayRunManually(task(prompt = ""), alreadyRunning = false))
    }

    @Test
    fun `next run after recurring task is in the future`() {
        val t = task(recurrence = Recurrence.INTERVAL, intervalMillis = 60 * 60_000L)
        val next = AutoPromptPolicy.nextRunAfter(t, nowMillis = 10_000L)
        assertTrue(next > 10_000L)
        assertEquals(0L, AutoPromptPolicy.nextRunAfter(task(recurrence = Recurrence.ONE_SHOT), nowMillis = 10_000L))
    }
}
