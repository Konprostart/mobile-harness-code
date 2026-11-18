package com.mobileharness.code.devtools

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Read-only snapshot of the device and runtime environment for the Developer Tools
 * panel. All values come from public, non-sensitive Android APIs and `/proc`/`/sys`
 * files; nothing here changes device state or reads personal data.
 */
data class DeviceInfoSnapshot(
    val abi: String,
    val sdkInt: Int,
    val androidRelease: String,
    val model: String,
    val manufacturer: String,
    val physicalRamMb: Long,
    val availableRamMb: Long,
    val cpuCount: Int,
    val cpuMaxFreqMhz: Long,
)

object DeviceInfo {
    fun collect(context: Context): DeviceInfoSnapshot {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        return DeviceInfoSnapshot(
            abi = abi,
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            model = Build.MODEL ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            physicalRamMb = if (memory.totalMem > 0) memory.totalMem / (1024L * 1024L) else 0L,
            availableRamMb = if (memory.availMem > 0) memory.availMem / (1024L * 1024L) else 0L,
            cpuCount = Runtime.getRuntime().availableProcessors(),
            cpuMaxFreqMhz = readCpuMaxFreqMhz(),
        )
    }

    private fun readCpuMaxFreqMhz(): Long {
        val khz = runCatching {
            File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
        }.getOrNull() ?: return 0L
        return if (khz > 0) khz / 1000L else 0L
    }
}
