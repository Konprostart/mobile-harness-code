package com.mobileharness.code.device

/**
 * A request to run one command through a privileged route. Immutable and free of Android
 * dependencies so the policy and validators are JVM-unit-testable.
 *
 * A request carries either a shell command string ([command], used by the root `su` transport)
 * or an argv list ([argv], used by the Shizuku `newProcess` transport). Both are validated
 * fail-closed by [PrivilegeCommand] before anything runs.
 */
data class PrivilegeRequest(
    val route: PrivilegeRoute,
    val level: PrivilegeLevel,
    val command: String = "",
    val argv: List<String> = emptyList(),
    val ecoEnabled: Boolean = false,
)
