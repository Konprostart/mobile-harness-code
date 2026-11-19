package com.mobileharness.code.device

/**
 * Root (UID 0) transport via the device's `su` binary (Magisk/KernelSU/APatch). This is the same
 * mechanism `libsu` wraps; we call `su` directly to avoid the JitPack-hosted `libsu` artifact and
 * keep every dependency on Maven Central under a permissive license.
 *
 * Execution is `su -c "<command>"`, which is inherently a trusted shell context — but the command
 * still passes [PrivilegePolicy] fail-closed checks (opt-in, available, sanitized) before this
 * executor is ever invoked.
 */
object SuRootExecutor : PrivilegeExecutor {
    override val route = PrivilegeRoute.ROOT
    override val level = PrivilegeLevel.ROOT

    override fun execute(request: PrivilegeRequest, timeoutMillis: Long, maxOutputBytes: Int): PrivilegeResult {
        val command = PrivilegeCommand.sanitizeCommand(request.command)
            ?: return PrivilegeResult.denied("invalid command")
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            ProcessRunner.run(process, timeoutMillis, maxOutputBytes)
        }.getOrElse {
            PrivilegeResult.error(it.message ?: "root execution failed")
        }
    }
}
