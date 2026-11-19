package com.mobileharness.code.network.ssh

import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the SSH lifecycle end-to-end and enforces TOFU before any credential is sent:
 *
 *  1. connect the transport and capture the server fingerprint (no auth yet),
 *  2. compare against the pinned fingerprint ([HostKeyStore.decide]):
 *     - first use → surface the fingerprint for explicit user confirmation,
 *     - match → authenticate,
 *     - mismatch → fail closed,
 *  3. then authenticate (password or private key) and run a command / start a tunnel.
 *
 * The controller is the only component that reads the [SshSecretStore]; credentials never travel
 * through the UI layer or into logs. Live tunnels are tracked per profile so a toggle can stop
 * them deterministically.
 */
class SshController(
    private val secretStore: SshSecretStore,
    private val client: SshClient = SshClient(),
    private val ecoEnabled: () -> Boolean = { false },
) {
    private val tunnels = ConcurrentHashMap<String, SshTunnel>()

    sealed interface TestOutcome {
        data class Connected(val fingerprint: String) : TestOutcome
        data class NeedsConfirmation(val fingerprint: String) : TestOutcome
        data class Failed(val message: String) : TestOutcome
    }

    /** Captures the server fingerprint without authenticating. Returns null on transport failure. */
    fun captureFingerprint(profile: SshProfile): String? {
        val result = client.connectTransport(profile)
        return when (result) {
            is SshConnectResult.Failure -> {
                log(profile, SshStage.ERROR, result.message)
                null
            }
            is SshConnectResult.Success -> {
                result.connection.close()
                result.fingerprint
            }
        }
    }

    /**
     * Full TOFU-gated connection test. On first use it returns [TestOutcome.NeedsConfirmation]
     * WITHOUT authenticating; after [confirmFingerprint] the next call proceeds to authentication.
     */
    fun testConnection(profile: SshProfile): TestOutcome {
        val pinned = secretStore.get(profile.id, SshSecretStore.Slot.HOST_KEY)
        return when (val result = client.connectTransport(profile)) {
            is SshConnectResult.Failure -> {
                log(profile, SshStage.ERROR, result.message)
                TestOutcome.Failed(result.message)
            }
            is SshConnectResult.Success -> {
                val connection = result.connection
                val fingerprint = result.fingerprint
                when (val decision = HostKeyStore.decide(pinned, fingerprint)) {
                    is TofuDecision.AcceptFirst -> {
                        connection.close()
                        log(profile, SshStage.CONNECT, "new server fingerprint $fingerprint")
                        TestOutcome.NeedsConfirmation(fingerprint)
                    }
                    is TofuDecision.Mismatch -> {
                        connection.close()
                        log(profile, SshStage.TOFU_MISMATCH, "pinned=${decision.pinned} presented=${decision.presented}")
                        TestOutcome.Failed("Server host key changed (possible MITM). Reset the pinned key to reconnect.")
                    }
                    is TofuDecision.Match -> authenticateAndProbe(profile, connection)
                }
            }
        }
    }

    /** Pins a fingerprint after explicit user confirmation on first use. */
    fun confirmFingerprint(profile: SshProfile, fingerprint: String) {
        secretStore.put(profile.id, SshSecretStore.Slot.HOST_KEY, fingerprint)
        log(profile, SshStage.TOFU_ACCEPT, fingerprint)
    }

    /** Clears a pinned fingerprint (manual reset after a key change warning). */
    fun resetFingerprint(profile: SshProfile) {
        secretStore.put(profile.id, SshSecretStore.Slot.HOST_KEY, "")
    }

    /** Runs one sanitized remote command, fail-closed on TOFU/auth errors. */
    fun runCommand(profile: SshProfile, command: String): SshExecResult {
        val sanitized = SshCommand.sanitize(command)
            ?: return SshExecResult.Failure("Invalid command.")
        val connection = connectVerified(profile)
            ?: return SshExecResult.Failure("Server not verified. Run \"Test connection\" first.")
        return authenticate(profile, connection).let { auth ->
            if (auth is SshAuthResult.Failure) {
                connection.close()
                log(profile, SshStage.AUTH, auth.message)
                SshExecResult.Failure(auth.message)
            } else {
                log(profile, SshStage.EXEC, sanitized.take(120))
                val exec = connection.exec(sanitized, ecoEnabled())
                connection.close()
                exec
            }
        }
    }

    /** Starts the configured tunnel for [profile], enforcing TOFU and authentication first. */
    fun startTunnel(profile: SshProfile): SshTunnelStartResult {
        if (isTunnelRunning(profile.id)) return SshTunnelStartResult.Failure("Tunnel is already running.")
        val connection = connectVerified(profile)
            ?: return SshTunnelStartResult.Failure("Server not verified. Run \"Test connection\" first.")
        return authenticate(profile, connection).let { auth ->
            if (auth is SshAuthResult.Failure) {
                connection.close()
                log(profile, SshStage.AUTH, auth.message)
                SshTunnelStartResult.Failure(auth.message)
            } else {
                when (val started = connection.startTunnel(profile.tunnelLocalPort, profile.tunnelRemoteHost, profile.tunnelRemotePort)) {
                    is SshTunnelStartResult.Success -> {
                        tunnels[profile.id] = started.tunnel
                        log(profile, SshStage.TUNNEL_START, "127.0.0.1:${started.localPort} → ${profile.tunnelRemoteHost}:${profile.tunnelRemotePort}")
                        started
                    }
                    is SshTunnelStartResult.Failure -> {
                        connection.close()
                        log(profile, SshStage.ERROR, started.message)
                        started
                    }
                }
            }
        }
    }

    fun stopTunnel(profile: SshProfile) {
        tunnels.remove(profile.id)?.let { tunnel ->
            tunnel.close()
            log(profile, SshStage.TUNNEL_STOP, "127.0.0.1:${tunnel.localPort}")
        }
    }

    fun isTunnelRunning(profileId: String): Boolean = tunnels[profileId]?.isRunning() == true

    /** Connects and verifies TOFU; returns a live connection on Match, or null (fail-closed). */
    private fun connectVerified(profile: SshProfile): SshConnection? {
        val pinned = secretStore.get(profile.id, SshSecretStore.Slot.HOST_KEY)
        return when (val result = client.connectTransport(profile)) {
            is SshConnectResult.Failure -> {
                log(profile, SshStage.ERROR, result.message)
                null
            }
            is SshConnectResult.Success -> {
                val connection = result.connection
                when (val decision = HostKeyStore.decide(pinned, result.fingerprint)) {
                    is TofuDecision.AcceptFirst -> {
                        connection.close()
                        log(profile, SshStage.CONNECT, "fingerprint not yet confirmed")
                        null
                    }
                    is TofuDecision.Mismatch -> {
                        connection.close()
                        log(profile, SshStage.TOFU_MISMATCH, "pinned=${decision.pinned} presented=${decision.presented}")
                        null
                    }
                    is TofuDecision.Match -> connection
                }
            }
        }
    }

    private fun authenticateAndProbe(profile: SshProfile, connection: SshConnection): TestOutcome {
        return authenticate(profile, connection).let { auth ->
            if (auth is SshAuthResult.Failure) {
                connection.close()
                log(profile, SshStage.AUTH, auth.message)
                TestOutcome.Failed(auth.message)
            } else {
                log(profile, SshStage.AUTH, "authenticated as ${profile.username}")
                val probe = connection.exec("true", ecoEnabled())
                connection.close()
                when (probe) {
                    is SshExecResult.Success -> {
                        log(profile, SshStage.SUCCESS, "connection OK")
                        TestOutcome.Connected(connection.fingerprint)
                    }
                    is SshExecResult.Failure -> {
                        log(profile, SshStage.ERROR, probe.message)
                        TestOutcome.Failed(probe.message)
                    }
                }
            }
        }
    }

    private fun authenticate(profile: SshProfile, connection: SshConnection): SshAuthResult = when (profile.authType) {
        SshAuthType.PASSWORD -> {
            val password = secretStore.get(profile.id, SshSecretStore.Slot.PASSWORD)
                ?: return SshAuthResult.Failure("No password stored for this server.")
            connection.authenticatePassword(profile.username, password)
        }
        SshAuthType.PRIVATE_KEY -> {
            val pem = secretStore.get(profile.id, SshSecretStore.Slot.PRIVATE_KEY)
                ?: return SshAuthResult.Failure("No private key stored for this server.")
            val passphrase = secretStore.get(profile.id, SshSecretStore.Slot.PASSPHRASE)
            when (val key = SshKeyLoader.load(pem, passphrase)) {
                is SshKeyLoader.Result.Failure -> SshAuthResult.Failure(key.message)
                is SshKeyLoader.Result.Success -> connection.authenticatePublicKey(profile.username, key.provider)
            }
        }
    }

    private fun log(profile: SshProfile, stage: SshStage, detail: String? = null) {
        SshLog.record(
            SshLogEntry(
                timestampMillis = System.currentTimeMillis(),
                profileName = profile.name.ifBlank { profile.endpointLabel() },
                stage = stage,
                detail = detail,
            ),
        )
    }
}
