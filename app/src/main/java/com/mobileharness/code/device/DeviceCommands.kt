package com.mobileharness.code.device

/**
 * A named, pre-classified device command the agent (or a user) can run through a privileged route.
 * The [risk] label is the single source of truth for whether a confirmation card is required; it
 * is computed by [DeviceCommandRisk] and never hand-authored, so a command cannot accidentally
 * be labelled safer than the classifier would judge it.
 */
data class DeviceCommand(
    val id: String,
    val label: String,
    val description: String,
    val command: String,
    val risk: DeviceCommandRisk = DeviceCommandRisk.of(command),
)

/**
 * A catalog of common Android device commands grouped by risk. READ entries are safe, deterministic
 * queries; CONTROL entries act on the device; DESTRUCTIVE entries are irreversible. The catalog is
 * intentionally a small, documented surface — the agent can also run arbitrary commands, but those
 * always flow through [DeviceCommandRisk] + [PrivilegePolicy] and are treated fail-closed.
 */
object DeviceCommandCatalog {
    /** Stable READ commands: device state, apps, battery, network, storage, processes, permissions. */
    val read: List<DeviceCommand> = listOf(
        DeviceCommand("device.model", "Device model", "Android build model and manufacturer.", "getprop ro.product.model"),
        DeviceCommand("device.version", "Android version", "Android OS version and SDK level.", "getprop ro.build.version.release"),
        DeviceCommand("device.sdk", "SDK level", "API level of the running Android.", "getprop ro.build.version.sdk"),
        DeviceCommand("device.props", "Build properties", "Full build property dump.", "getprop"),
        DeviceCommand("apps.installed", "Installed apps", "List of installed packages.", "pm list packages"),
        DeviceCommand("apps.enabled", "Enabled apps", "List of enabled packages.", "pm list packages -e"),
        DeviceCommand("apps.disabled", "Disabled apps", "List of disabled packages.", "pm list packages -d"),
        DeviceCommand("apps.thirdparty", "Third-party apps", "List of third-party packages.", "pm list packages -3"),
        DeviceCommand("apps.features", "Device features", "List of declared system features.", "pm list features"),
        DeviceCommand("battery.level", "Battery level", "Current battery level via dumpsys.", "dumpsys battery"),
        DeviceCommand("network.interfaces", "Network interfaces", "Configured network interfaces.", "ip addr"),
        DeviceCommand("network.routes", "Network routes", "Kernel routing table.", "ip route"),
        DeviceCommand("storage.mounts", "Storage mounts", "Mounted filesystems.", "df -h"),
        DeviceCommand("storage.stats", "Storage stats", "Disk usage of /data.", "du -sh /data"),
        DeviceCommand("processes.list", "Processes", "Running processes snapshot.", "ps -A"),
        DeviceCommand("memory.info", "Memory info", "Kernel memory information.", "cat /proc/meminfo"),
        DeviceCommand("cpu.info", "CPU info", "Kernel CPU information.", "cat /proc/cpuinfo"),
        DeviceCommand("permissions.granted", "Granted permissions", "Packages with granted runtime permissions.", "dumpsys package permissions"),
        DeviceCommand("settings.list", "List settings", "List a settings namespace.", "settings list system"),
        DeviceCommand("window.size", "Window size", "Current display resolution.", "wm size"),
        DeviceCommand("window.density", "Display density", "Current display density.", "wm density"),
        DeviceCommand("activity.current", "Current activity", "Foreground activity stack.", "dumpsys activity activities"),
    )

    /** CONTROL commands: open apps, screenshots, input events. */
    val control: List<DeviceCommand> = listOf(
        DeviceCommand("app.open", "Open app", "Start an app's launch activity.", "monkey -p com.android.settings 1"),
        DeviceCommand("screen.screenshot", "Screenshot", "Capture the current screen.", "screencap -p /sdcard/screen.png"),
        DeviceCommand("input.keyevent", "Key event", "Send a key event (e.g. HOME).", "input keyevent 3"),
    )

    /** DESTRUCTIVE commands — always require per-action confirmation. */
    val destructive: List<DeviceCommand> = listOf(
        DeviceCommand("app.uninstall", "Uninstall app", "Uninstall a package by name.", "pm uninstall com.example.app"),
        DeviceCommand("app.clear", "Clear app data", "Clear an app's data.", "pm clear com.example.app"),
        DeviceCommand("app.disable", "Disable app", "Disable a package.", "pm disable-user com.example.app"),
        DeviceCommand("file.remove", "Remove file", "Delete a file path.", "rm /sdcard/file"),
        DeviceCommand("system.reboot", "Reboot device", "Reboot the device.", "reboot"),
    )

    fun byId(id: String): DeviceCommand? =
        (read + control + destructive).firstOrNull { it.id == id }

    fun all(): List<DeviceCommand> = read + control + destructive
}
