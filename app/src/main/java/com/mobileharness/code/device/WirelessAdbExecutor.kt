package com.mobileharness.code.device

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Wireless ADB transport (Android 11+ "Wireless debugging"): connects to the device's own `adbd`
 * over the configured host:port, authenticates with an already-paired [AdbKey.Pair], and runs a
 * single shell command. Authorization is still fail-closed through [PrivilegePolicy] (opt-in,
 * available, authorized, level match, sanitized argv) before this executor is ever invoked.
 *
 * First-time pairing (the SPAKE2 exchange that registers the key with `adbd`) is documented in
 * [AdbPairing] and is intentionally not performed here: this executor only authenticates with a key
 * `adbd` already trusts. When authentication fails it returns a clean, honest error rather than
 * falling back to another route.
 */
object WirelessAdbExecutor : PrivilegeExecutor {
    override val route = PrivilegeRoute.WIRELESS_ADB
    override val level = PrivilegeLevel.SHELL

    /** "host:port" endpoint, set by the app from the configured wireless-debugging port. */
    @Volatile
    var endpoint: String? = null

    /** The paired ADB key, loaded/set by the app. */
    @Volatile
    var key: AdbKey.Pair? = null

    override fun execute(request: PrivilegeRequest, timeoutMillis: Long, maxOutputBytes: Int): PrivilegeResult {
        val argv = PrivilegeCommand.sanitizeArgv(request.argv)
            ?: return PrivilegeResult.denied("invalid argv")
        val target = endpoint?.trim().orEmpty()
        if (target.isEmpty()) return PrivilegeResult.denied("wireless ADB endpoint not configured")
        val pair = key ?: return PrivilegeResult.denied("wireless ADB key not paired")
        val parsed = parseEndpoint(target) ?: return PrivilegeResult.denied("invalid wireless ADB endpoint")

        return runCatching {
            val command = argv.joinToString(" ") { shellQuote(it) }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(parsed.first, parsed.second), 10_000)
                socket.soTimeout = timeoutMillis.coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt()
                val conn = AdbConnection(socket.getInputStream(), socket.getOutputStream())
                conn.connect(pair)
                val result = conn.runShell(command, maxOutputBytes)
                PrivilegeResult(
                    outcome = PrivilegeOutcome.SUCCESS,
                    exitCode = 0,
                    stdout = result.stdout,
                    stderr = result.stderr,
                )
            }
        }.getOrElse { e ->
            when (e) {
                is AdbConnection.AdbException -> PrivilegeResult.denied(e.message ?: "wireless ADB auth failed")
                else -> PrivilegeResult.error(e.message ?: "wireless ADB execution failed")
            }
        }
    }

    private fun parseEndpoint(target: String): Pair<String, Int>? {
        val idx = target.lastIndexOf(':')
        if (idx <= 0 || idx == target.length - 1) return null
        val host = target.substring(0, idx).trim()
        val port = target.substring(idx + 1).toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    /** POSIX single-quote quoting for argv → shell command assembly. */
    internal fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
}
