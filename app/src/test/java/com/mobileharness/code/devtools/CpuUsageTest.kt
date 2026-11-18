package com.mobileharness.code.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuUsageTest {

    @Test
    fun parsesAggregateAndCores() {
        val sample = CpuUsageReader.parseProcStat(
            """
            cpu  1000 0 200 3000 100 0 0 0 0 0
            cpu0 500 0 100 1500 50 0 0 0 0 0
            cpu1 500 0 100 1500 50 0 0 0 0 0
            intr 123 0
            """.trimIndent(),
        )!!
        assertEquals(4300L, sample.total)
        assertEquals(3100L, sample.idle) // idle 3000 + iowait 100
        assertEquals(2, sample.cores.size)
        assertEquals(2150L, sample.cores[0].total)
        assertEquals(1550L, sample.cores[0].idle)
    }

    @Test
    fun returnsNullWhenNoAggregateLine() {
        assertNull(CpuUsageReader.parseProcStat("intr 123 0\nctxt 456\n"))
    }

    @Test
    fun computesTotalAndPerCoreUsageFromDelta() {
        val prev = CpuSample(total = 1000, idle = 800, cores = listOf(CpuCoreSample(0, 1000, 800)))
        val curr = CpuSample(total = 1500, idle = 1100, cores = listOf(CpuCoreSample(0, 1500, 1100)))
        val usage = CpuUsageReader.computeUsage(prev, curr)
        assertEquals(40, usage.totalPercent)
        assertEquals(listOf(40), usage.perCorePercent)
    }

    @Test
    fun zeroDeltaYieldsZeroPercent() {
        val sample = CpuSample(total = 1000, idle = 800, cores = listOf(CpuCoreSample(0, 1000, 800)))
        val usage = CpuUsageReader.computeUsage(sample, sample)
        assertEquals(0, usage.totalPercent)
        assertEquals(listOf(0), usage.perCorePercent)
    }

    @Test
    fun usageIsClampedToHundred() {
        // A negative idle delta (should not happen) must not exceed 100%.
        val prev = CpuSample(total = 1000, idle = 1000, cores = emptyList())
        val curr = CpuSample(total = 2000, idle = 500, cores = emptyList())
        val usage = CpuUsageReader.computeUsage(prev, curr)
        assertEquals(100, usage.totalPercent)
    }
}
