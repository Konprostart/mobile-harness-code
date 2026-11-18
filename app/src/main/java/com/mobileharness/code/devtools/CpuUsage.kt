package com.mobileharness.code.devtools

import java.io.File

/**
 * CPU usage reader for the Developer Tools monitor.
 *
 * CPU utilisation has no direct Android API, so we read `/proc/stat` twice with a
 * short delay and compute the delta. `/proc/stat` reports cumulative jiffies since
 * boot for the whole CPU and for each core (`cpu`, `cpu0`, `cpu1`, ...).
 *
 * The parsing and percentage math are pure functions so they can be unit-tested
 * against captured `/proc/stat` snapshots without a device.
 */
data class CpuCoreSample(
    val index: Int,
    val total: Long,
    val idle: Long,
)

data class CpuSample(
    val total: Long,
    val idle: Long,
    val cores: List<CpuCoreSample>,
)

data class CpuUsage(
    val totalPercent: Int,
    val perCorePercent: List<Int>,
)

object CpuUsageReader {

    /** Parses a `/proc/stat` document. Returns null when no `cpu` aggregate line is present. */
    fun parseProcStat(text: String): CpuSample? {
        var aggregate: CpuSample? = null
        val cores = mutableListOf<CpuCoreSample>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val label = parts[0]
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.isEmpty()) continue
            val total = nums.sum()
            // idle = idle (index 3) + iowait (index 4)
            val idle = (nums.getOrNull(3) ?: 0L) + (nums.getOrNull(4) ?: 0L)
            when {
                label == "cpu" -> aggregate = CpuSample(total, idle, emptyList())
                label.length > 3 && label.startsWith("cpu") -> {
                    label.removePrefix("cpu").toIntOrNull()?.let { index ->
                        cores.add(CpuCoreSample(index, total, idle))
                    }
                }
            }
        }
        val base = aggregate ?: return null
        return base.copy(cores = cores.sortedBy { it.index })
    }

    /** Reads `/proc/stat`; null when unavailable (e.g. restricted sandbox). */
    fun read(): CpuSample? = runCatching { File("/proc/stat").readText() }.getOrNull()?.let(::parseProcStat)

    /** Computes utilisation as a percentage from two samples; returns null for a bad delta. */
    fun computeUsage(previous: CpuSample, current: CpuSample): CpuUsage {
        val total = percentage(previous.total, previous.idle, current.total, current.idle)
        val perCore = current.cores.map { core ->
            val prev = previous.cores.firstOrNull { it.index == core.index }
            if (prev == null) 0 else percentage(prev.total, prev.idle, core.total, core.idle)
        }
        return CpuUsage(totalPercent = total, perCorePercent = perCore)
    }

    /** Two reads [delayMs] apart, reduced to one usage snapshot (null when unreadable). */
    fun readUsageNow(delayMs: Long = 300L): CpuUsage? {
        val first = read() ?: return null
        Thread.sleep(delayMs.coerceAtLeast(10L))
        val second = read() ?: return null
        return computeUsage(first, second)
    }

    private fun percentage(prevTotal: Long, prevIdle: Long, currTotal: Long, currIdle: Long): Int {
        val totalDelta = currTotal - prevTotal
        val idleDelta = currIdle - prevIdle
        if (totalDelta <= 0L) return 0
        return ((totalDelta - idleDelta) * 100L / totalDelta).toInt().coerceIn(0, 100)
    }
}
