package com.mobileharness.code.telegram

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.mobileharness.code.BuildConfig
import com.mobileharness.code.data.AppPreferences
import com.mobileharness.code.model.AgentKind
import java.io.File

/** Pure read-only status lines for the `/status`, `/version` and `/projects` commands. */
class TelegramStatus(private val context: Context) {
    private val preferences = AppPreferences(context)

    fun appVersion(): String = buildString {
        append("Mobile Harness Code")
        append('\n')
        append("Version ").append(BuildConfig.VERSION_NAME)
        append(" (").append(BuildConfig.VERSION_CODE).append(')')
        append(" · variant ").append(BuildConfig.APP_VARIANT)
    }

    fun deviceStatus(): String = buildString {
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        append('\n')
        append("ABIs: ").append(Build.SUPPORTED_ABIS.joinToString(", "))
        append('\n')
        append("RAM: ").append(formatBytes(totalRam())).append(" total / ").append(formatBytes(availableRam())).append(" free")
        append('\n')
        append("Storage: ").append(formatBytes(availableStorage())).append(" free")
        append('\n')
        append("Runtime: ").append(if (preferences.runtimeSetupComplete) "installed" else "not installed")
        append('\n')
        append("Agent: ").append(AgentKind.fromStored(preferences.agentKind).title)
    }

    fun projectsSummary(): String {
        val projects = preferences.loadProjects()
        if (projects.isEmpty()) return "No projects yet."
        val lines = projects.sortedByDescending { it.updatedAtMillis }.take(10).map { project ->
            val type = if (project.type.name == "ANDROID") " · Android" else ""
            "${project.name} (${project.formattedUpdatedAt})$type"
        }
        val extra = if (projects.size > 10) "\n… and ${projects.size - 10} more" else ""
        return lines.joinToString("\n") + extra
    }

    private fun totalRam(): Long = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
    }.getOrDefault(0L)

    private fun availableRam(): Long = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
    }.getOrDefault(0L)

    private fun availableStorage(): Long = runCatching {
        StatFs(File(context.filesDir, "..").absolutePath).let { stat -> stat.availableBytes }
    }.getOrDefault(0L)

    private fun formatBytes(bytes: Long): String =
        android.text.format.Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))
}
