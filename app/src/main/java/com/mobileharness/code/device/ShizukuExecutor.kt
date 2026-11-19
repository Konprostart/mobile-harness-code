package com.mobileharness.code.device

import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Shell/root transport through the Shizuku service. Runs a process via
 * `IShizukuService.newProcess`, which executes it under the Shizuku server's identity (UID 2000
 * shell when Shizuku is started over ADB, UID 0 when started with root). Permission is checked
 * by [PrivilegeDetector] before this executor is reached.
 */
object ShizukuExecutor : PrivilegeExecutor {
    override val route = PrivilegeRoute.SHIZUKU
    override val level = PrivilegeLevel.SHELL

    override fun execute(request: PrivilegeRequest, timeoutMillis: Long, maxOutputBytes: Int): PrivilegeResult {
        val argv = PrivilegeCommand.sanitizeArgv(request.argv)
            ?: return PrivilegeResult.denied("invalid argv")
        return runCatching {
            val binder = Shizuku.getBinder() ?: return PrivilegeResult.denied("Shizuku binder not available")
            val service = IShizukuService.Stub.asInterface(binder)
                ?: return PrivilegeResult.denied("Shizuku service not reachable")
            val remote = service.newProcess(argv.toTypedArray(), null, null)
            val process = ShizukuProcess(remote)
            ProcessRunner.run(process, timeoutMillis, maxOutputBytes)
        }.getOrElse {
            PrivilegeResult.error(it.message ?: "Shizuku execution failed")
        }
    }
}
