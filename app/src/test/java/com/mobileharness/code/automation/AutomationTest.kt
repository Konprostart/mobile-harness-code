package com.mobileharness.code.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {

    private fun task(id: String = "t1", recurrence: Recurrence = Recurrence.ONE_SHOT) = ScheduledTask(
        id = id,
        agentKind = "claude",
        projectId = "p1",
        prompt = "Run the tests",
        recurrence = recurrence,
        nextRunMillis = 1_000L,
        intervalMillis = if (recurrence == Recurrence.INTERVAL) 60 * 60_000L else 0L,
    )

    @Test
    fun `valid task passes and unknown agent is rejected`() {
        assertTrue(SchedulePolicy.isValid(task(), ecoEnabled = false))
        assertFalse(SchedulePolicy.isValid(task().copy(agentKind = "unknown"), ecoEnabled = false))
    }

    @Test
    fun `interval below minimum is rejected and eco raises the floor`() {
        val tooFast = task(recurrence = Recurrence.INTERVAL).copy(intervalMillis = 60_000L)
        assertFalse(SchedulePolicy.isValid(tooFast, ecoEnabled = false))
        val sixHours = task(recurrence = Recurrence.INTERVAL).copy(intervalMillis = 6 * 60 * 60_000L)
        assertTrue(SchedulePolicy.isValid(sixHours, ecoEnabled = true))
        assertFalse(SchedulePolicy.isValid(sixHours.copy(intervalMillis = 30 * 60_000L), ecoEnabled = true))
    }

    @Test
    fun `eco mode defers background work during quiet hours but not reminders`() {
        val agent = task()
        assertTrue(SchedulePolicy.isRunAllowedNow(agent, ecoEnabled = true, hourOfDay = 12))
        assertFalse(SchedulePolicy.isRunAllowedNow(agent, ecoEnabled = true, hourOfDay = 3))
        assertFalse(SchedulePolicy.isRunAllowedNow(agent, ecoEnabled = true, hourOfDay = 23))
        val reminder = task().copy(agentKind = "reminder", projectId = "", prompt = "")
        assertTrue(SchedulePolicy.isRunAllowedNow(reminder, ecoEnabled = true, hourOfDay = 3))
    }

    @Test
    fun `parser handles once at and every`() {
        val once = ScheduleParser.parse("once at 1700000000000", nowMillis = 0L, ecoEnabled = false)
        assertEquals(Recurrence.ONE_SHOT, once!!.recurrence)
        assertEquals(1_700_000_000_000L, once.nextRunMillis)

        val every = ScheduleParser.parse("every 2h", nowMillis = 0L, ecoEnabled = false)
        assertEquals(Recurrence.INTERVAL, every!!.recurrence)
        assertEquals(2 * 60 * 60_000L, every.intervalMillis)
    }

    @Test
    fun `parser rejects unknown and too-fast intervals`() {
        assertNull(ScheduleParser.parse("gibberish", 0L, ecoEnabled = false))
        assertNull(ScheduleParser.parse("every 1m", 0L, ecoEnabled = false)) // below 30m floor
    }

    @Test
    fun `store round trips and enforces policy`() {
        val store = SchedulerStore()
        assertTrue(store.upsert(task()))
        assertFalse(store.upsert(task().copy(agentKind = "bad")))
        val json = ScheduledTaskCodec.encode(store.snapshot())
        val restored = SchedulerStore()
        restored.load(json)
        assertEquals(1, restored.snapshot().size)
        assertEquals("t1", restored.snapshot().first().id)
    }
}
