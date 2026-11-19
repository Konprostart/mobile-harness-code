package com.mobileharness.code.network.ssh

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider

/** Outcome of establishing the SSH transport (before any authentication). */
sealed interface SshConnectResult {
    data class Success(val connection: SshConnection, val fingerprint: String) : SshConnectResult
    data class Failure(val message: String) : SshConnectResult
}

/** Outcome of user authentication. */
sealed interface SshAuthResult {
    object Success : SshAuthResult
    data class Failure(val message: String) : SshAuthResult
}

/** Outcome of running a single remote command. */
sealed interface SshExecResult {
    data class Success(val stdout: String, val stderr: String, val exitStatus: Int) : SshExecResult
    data class Failure(val message: String) : SshExecResult
}

/** Outcome of starting a local port forwarding tunnel. */
sealed interface SshTunnelStartResult {
    data class Success(val tunnel: SshTunnel, val localPort: Int) : SshTunnelStartResult
    data class Failure(val message: String) : SshTunnelStartResult
}

/**
 * Thin, fail-closed wrapper over sshj. [connectTransport] establishes the SSH transport with a
 * promiscuous host-key verifier and captures the server fingerprint — but it NEVER authenticates,
 * so no password or key is sent before TOFU has passed. The caller performs the TOFU decision on
 * the returned [SshConnection.fingerprint] and then calls the appropriate `authenticate*` method.
 */
class SshClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    fun connectTransport(profile: SshProfile): SshConnectResult {
        val ssh = SSHClient()
        val captured = AtomicReference<PublicKey?>(null)
        return runCatching {
            ssh.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                    captured.set(key)
                    // Accept at the transport layer: the real TOFU decision happens before auth,
                    // so no credential is ever sent until the fingerprint has been confirmed.
                    return true
                }

                override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
            })
            ssh.setConnectTimeout(connectTimeoutMillis)
            ssh.setTimeout(readTimeoutMillis)
            ssh.connect(profile.host, profile.port)
            val fingerprint = fingerprintOf(captured.get())
            SshConnectResult.Success(SshConnection(ssh, fingerprint), fingerprint)
        }.getOrElse {
            runCatching { ssh.disconnect() }
            SshConnectResult.Failure(it.message ?: "SSH connection failed")
        }
    }

    private fun fingerprintOf(publicKey: PublicKey?): String =
        HostKeyStore.fingerprint(publicKey?.encoded ?: byteArrayOf())
}

/**
 * A connected, unauthenticated SSH transport. Authentication and channel operations are exposed
 * here; once [startTunnel] succeeds the returned [SshTunnel] owns the transport lifecycle, so the
 * caller must not call [close] on this connection for the lifetime of that tunnel.
 */
class SshConnection internal constructor(
    private val ssh: SSHClient,
    val fingerprint: String,
) {
    fun authenticatePassword(username: String, password: String): SshAuthResult = runCatching {
        ssh.authPassword(username, password)
        SshAuthResult.Success
    }.getOrElse { SshAuthResult.Failure(it.message ?: "Authentication failed") }

    fun authenticatePublicKey(username: String, provider: FileKeyProvider): SshAuthResult = runCatching {
        ssh.authPublickey(username, provider)
        SshAuthResult.Success
    }.getOrElse { SshAuthResult.Failure(it.message ?: "Authentication failed") }

    /** Executes a single remote command, returning bounded stdout/stderr and the exit status. */
    fun exec(command: String, ecoEnabled: Boolean): SshExecResult = runCatching {
        val session = ssh.startSession()
        try {
            val cmd = session.exec(command)
            cmd.join(SshCommand.timeoutMillis(ecoEnabled), TimeUnit.MILLISECONDS)
            val maxChars = SshCommand.maxOutputChars(ecoEnabled)
            val stdout = readLimited(cmd.inputStream, maxChars)
            val stderr = readLimited(cmd.errorStream, maxChars)
            SshExecResult.Success(stdout, stderr, cmd.exitStatus ?: -1)
        } finally {
            runCatching { session.close() }
        }
    }.getOrElse { SshExecResult.Failure(it.message ?: "Command execution failed") }

    /**
     * Starts a local port forwarding tunnel bound exclusively to `127.0.0.1` (never wildcard),
     * forwarding to `remoteHost:remotePort` on the server. The returned [SshTunnel] owns this
     * transport from here on.
     */
    fun startTunnel(localPort: Int, remoteHost: String, remotePort: Int): SshTunnelStartResult = runCatching {
        val serverSocket = ServerSocket()
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        val parameters = Parameters("127.0.0.1", localPort, remoteHost, remotePort)
        val forwarder = ssh.newLocalPortForwarder(parameters, serverSocket)
        val thread = Thread({ runCatching { forwarder.listen() } }, "ssh-tunnel-$localPort")
        thread.isDaemon = true
        thread.start()
        SshTunnelStartResult.Success(SshTunnel(ssh, forwarder, serverSocket, thread, localPort), localPort)
    }.getOrElse { SshTunnelStartResult.Failure(it.message ?: "Could not start tunnel") }

    fun close() {
        runCatching { ssh.disconnect() }
    }

    private fun readLimited(stream: InputStream, maxChars: Int): String =
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(4096)
            var total = 0
            while (total < maxChars) {
                val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - total))
                if (count <= 0) break
                builder.append(buffer, 0, count)
                total += count
            }
            builder.toString()
        }
}
