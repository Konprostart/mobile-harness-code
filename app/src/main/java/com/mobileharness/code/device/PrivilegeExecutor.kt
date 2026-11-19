package com.mobileharness.code.device

/**
 * A transport that can run a privileged command. Implementations live in this package
 * ([SuRootExecutor], [ShizukuExecutor]) and are Android-only; the interface itself stays pure so
 * [PrivilegePolicy] and [PrivilegeController] can be reasoned about and tested independently.
 */
interface PrivilegeExecutor {
    val route: PrivilegeRoute
    val level: PrivilegeLevel

    fun execute(request: PrivilegeRequest, timeoutMillis: Long, maxOutputBytes: Int): PrivilegeResult
}
