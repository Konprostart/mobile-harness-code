package com.mobileharness.code.device

/**
 * A detected state for one privileged route. Produced by [PrivilegeDetector] and consumed by the
 * "Phone Control" UI and by [PrivilegePolicy] before any command is executed.
 *
 *  - [available]   the transport is actually present (Shizuku binder alive, `su` found, ADB on).
 *  - [authorized]  the transport has been granted to this app (Shizuku permission; always true
 *                  for root and ADB once the transport is available and the user opted in).
 *  - [level]       the Linux privilege level this route can currently provide.
 */
data class PrivilegeRouteState(
    val route: PrivilegeRoute,
    val available: Boolean,
    val authorized: Boolean,
    val level: PrivilegeLevel,
    val detail: String? = null,
) {
    val usable: Boolean get() = available && authorized && level != PrivilegeLevel.NONE
}
