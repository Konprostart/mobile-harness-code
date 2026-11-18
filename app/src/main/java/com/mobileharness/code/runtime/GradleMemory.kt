package com.mobileharness.code.runtime

import android.app.ActivityManager
import android.content.Context
import com.mobileharness.code.data.AppPreferences
import java.io.File

/**
 * Memory profile for the on-device Android toolchain (the private Linux runtime's
 * Gradle, plus every Android project template we generate on the phone).
 *
 * On-device builds run inside a PRoot guest with a fraction of the phone's RAM, so a
 * one-size-fits-all `-Xmx3g` heap from a desktop project would trigger
 * `OutOfMemoryError: Metaspace` or system-level kills. Instead we derive a profile
 * from the physical RAM and let the user override it manually in Settings.
 */
data class GradleMemoryProfile(
    val maxHeapMb: Int,
    val maxMetaspaceMb: Int,
) {
    init {
        require(maxHeapMb > 0) { "maxHeapMb must be positive" }
        require(maxMetaspaceMb > 0) { "maxMetaspaceMb must be positive" }
    }

    /** Full `org.gradle.jvmargs` value, usable in gradle.properties and `-D` flags. */
    val jvmArgs: String
        get() = "-Xmx${maxHeapMb}m -XX:MaxMetaspaceSize=${maxMetaspaceMb}m -Dfile.encoding=UTF-8"

    companion object {
        /**
         * The tuned values the project calls for: enough heap for a Compose build on a
         * mid-range phone, plus an explicit metaspace cap to stop Metaspace exhaustion.
         */
        val DEFAULT = GradleMemoryProfile(maxHeapMb = 1536, maxMetaspaceMb = 768)
    }
}

object GradleMemory {
    const val JVMARGS_KEY = "org.gradle.jvmargs"

    private val managedLine = Regex("^\\s*${Regex.escape(JVMARGS_KEY)}\\s*[:=].*$")

    /**
     * Maps the device's physical RAM to a conservative on-device heap. Heaps stay well
     * below the full RAM because the Linux guest, the host app and the Android system
     * all share the same physical memory. Manual override in Settings bypasses this.
     */
    fun recommendedForRamMb(ramMb: Long): GradleMemoryProfile = when {
        ramMb < 2560 -> GradleMemoryProfile(maxHeapMb = 768, maxMetaspaceMb = 256)
        ramMb < 3584 -> GradleMemoryProfile(maxHeapMb = 1024, maxMetaspaceMb = 384)
        ramMb < 6144 -> GradleMemoryProfile.DEFAULT // e.g. a 3.6 GiB phone
        else -> GradleMemoryProfile(maxHeapMb = 2048, maxMetaspaceMb = 1024)
    }

    /** Physical RAM in MiB read from `/proc/meminfo`, or 0 when unavailable. */
    fun deviceRamMb(): Long {
        val meminfo = runCatching { File("/proc/meminfo").readText() }.getOrNull() ?: return 0L
        val totalKb = meminfo.lineSequence()
            .firstOrNull { it.startsWith("MemTotal") }
            ?.let { line -> Regex("\\d+").find(line)?.value?.toLongOrNull() }
            ?: return 0L
        return if (totalKb > 0) totalKb / 1024 else 0L
    }

    /** Physical RAM in MiB, falling back to [ActivityManager] when `/proc` is unavailable. */
    fun deviceRamMb(context: Context): Long {
        val fromProc = deviceRamMb()
        if (fromProc > 0) return fromProc
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(0L)
    }

    /**
     * The profile the on-device toolchain should actually use: a valid manual override
     * wins, otherwise the RAM-derived recommendation. A missing `/proc` and manual
     * override falls back to [GradleMemoryProfile.DEFAULT].
     */
    fun resolve(context: Context, preferences: AppPreferences): GradleMemoryProfile {
        if (!preferences.gradleMemoryAuto) {
            val heap = preferences.gradleMaxHeapMb
            val metaspace = preferences.gradleMaxMetaspaceMb
            if (heap > 0 && metaspace > 0) return GradleMemoryProfile(heap, metaspace)
        }
        val ram = deviceRamMb(context)
        return if (ram > 0) recommendedForRamMb(ram) else GradleMemoryProfile.DEFAULT
    }

    /**
     * Rewrites the `org.gradle.jvmargs` line of a `gradle.properties` document in place,
     * appending it when absent. Other lines and formatting are preserved.
     */
    fun applyJvmArgs(content: String, profile: GradleMemoryProfile): String {
        val replacement = "$JVMARGS_KEY=${profile.jvmArgs}"
        var replaced = false
        val lines = content.lineSequence().map { line ->
            if (!replaced && managedLine.matches(line)) {
                replaced = true
                replacement
            } else {
                line
            }
        }.toMutableList()
        if (!replaced) lines += replacement
        return lines.joinToString("\n").trimEnd() + "\n"
    }
}
