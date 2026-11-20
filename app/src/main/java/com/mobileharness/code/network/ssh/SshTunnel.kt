package com.mobileharness.code.network.ssh

import java.net.ServerSocket
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder

/**
 * A live SSH local-port-forwarding tunnel. Owns the SSH transport from the moment it is created:
 * closing the tunnel closes the forwarder, the loopback server socket and the SSH connection, so
 * a stopped tunnel can never leave a half-open listener behind.
 */
class SshTunnel internal constructor(
    private val ssh: SSHClient,
    private val forwarder: LocalPortForwarder,
    private val serverSocket: ServerSocket,
    private val thread: Thread,
    val localPort: Int,
) {
    /**
     * Whether the tunnel is still genuinely usable: the accept loop is alive, the loopback socket
     * is open, and the SSH transport it forwards over is still connected and authenticated. The
     * transport checks are what catch a tunnel whose SSH link died in the background (Doze or a
     * network drop) while the listener thread and socket still look healthy from the outside — so
     * a foreground status refresh can report it as "stopped" instead of lying as "running".
     */
    fun isRunning(): Boolean =
        thread.isAlive && !serverSocket.isClosed && ssh.isConnected && ssh.isAuthenticated

    /** Idempotent shutdown: interrupt the accept loop, close the socket, then drop the SSH transport. */
    fun close() {
        runCatching { forwarder.close() }
        runCatching { serverSocket.close() }
        runCatching { thread.interrupt() }
        runCatching { ssh.disconnect() }
    }
}
