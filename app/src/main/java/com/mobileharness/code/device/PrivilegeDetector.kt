package com.mobileharness.code.device

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Detects the presence and authorization state of the three privileged routes without executing
 * anything. Detection is read-only and never triggers a root prompt or a Shizuku permission
 * dialog: the `su` probe only checks for the binary, and Shizuku state is read from the live
 * binder.
 *
 * The results feed the "Phone Control" UI and [PrivilegePolicy]; the user's per-route opt-in is
 * a separate, persisted flag applied later, so detection never silently enables anything.
 */
object PrivilegeDetector {

    private val SU_CANDIDATES = listOf(
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/system/app/Superuser/Superuser.apk",
        "/system/app/SuperSU/SuperSU.apk",
    )

    fun detect(context: Context): List<PrivilegeRouteState> = listOf(
        detectShizuku(),
        detectWirelessAdb(context),
        detectRoot(),
    )

    private fun detectShizuku(): PrivilegeRouteState = runCatching {
        val available = Shizuku.pingBinder()
        if (!available) {
            return PrivilegeRouteState(
                route = PrivilegeRoute.SHIZUKU,
                available = false,
                authorized = false,
                level = PrivilegeLevel.NONE,
                detail = "Shizuku service not running",
            )
        }
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val uid = Shizuku.getUid()
        val level = if (uid == 0) PrivilegeLevel.ROOT else PrivilegeLevel.SHELL
        PrivilegeRouteState(
            route = PrivilegeRoute.SHIZUKU,
            available = true,
            authorized = granted,
            level = level,
            detail = if (granted) "uid=$uid" else "permission not granted",
        )
    }.getOrElse {
        PrivilegeRouteState(
            route = PrivilegeRoute.SHIZUKU,
            available = false,
            authorized = false,
            level = PrivilegeLevel.NONE,
            detail = it.message ?: "unavailable",
        )
    }

    private fun detectWirelessAdb(context: Context): PrivilegeRouteState {
        val adbEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        // Wireless ADB only means "debugging over Wi-Fi is on"; turning that into a shell
        // requires the SPAKE2 pairing flow, which is not implemented in this phase. Report the
        // transport as detected but not authorized so the policy stays fail-closed.
        return PrivilegeRouteState(
            route = PrivilegeRoute.WIRELESS_ADB,
            available = adbEnabled,
            authorized = false,
            level = PrivilegeLevel.SHELL,
            detail = if (adbEnabled) "ADB enabled; pairing flow not implemented" else "ADB disabled",
        )
    }

    private fun detectRoot(): PrivilegeRouteState {
        val suFound = SU_CANDIDATES.any { File(it).exists() }
        return PrivilegeRouteState(
            route = PrivilegeRoute.ROOT,
            available = suFound,
            authorized = suFound,
            level = if (suFound) PrivilegeLevel.ROOT else PrivilegeLevel.NONE,
            detail = if (suFound) "su binary present" else "no su binary",
        )
    }
}
