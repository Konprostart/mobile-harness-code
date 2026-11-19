package com.mobileharness.code.device

/**
 * The transport a privileged command can take. All three routes are strictly opt-in and
 * fail-closed: nothing runs unless the user enabled the route, the transport is actually
 * present, and (for Shizuku) the user granted permission in the Shizuku manager app.
 */
enum class PrivilegeRoute(val stableId: String) {
    /** `adb shell`-level (UID 2000) access through the Shizuku service, without root. */
    SHIZUKU("shizuku"),

    /** Shell access via the Android Wireless Debugging / SPAKE2 pairing path (Android 11+). */
    WIRELESS_ADB("wireless_adb"),

    /** Root (UID 0) access through the device's `su` binary (Magisk/KernelSU/APatch). */
    ROOT("root"),
    ;

    companion object {
        fun fromStableId(id: String): PrivilegeRoute? = entries.firstOrNull { it.stableId == id }
    }
}

/**
 * The Linux privilege level a route can provide. Ordered so a policy check can reject a request
 * that asks for more privilege than the selected route can deliver.
 */
enum class PrivilegeLevel(val rank: Int) {
    NONE(0),
    SHELL(1),
    ROOT(2),
}
