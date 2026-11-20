package com.mobileharness.code.network.ssh

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the v1.3.13 background-hardening rule for [SshTunnel.isRunning]: a tunnel must report
 * "running" only while the accept loop, the loopback socket AND the live SSH transport are all
 * healthy. A tunnel whose SSH link died in the background (Doze / network drop) must report
 * "stopped" even when its listener thread and socket still look alive from the outside.
 */
class SshTunnelTest {

    /** sshj's SSHClient is concrete, so override the two transport predicates we now rely on. */
    private class FakeSsh(
        var connected: Boolean,
        var authenticated: Boolean,
    ) : SSHClient() {
        override fun isConnected(): Boolean = connected
        override fun isAuthenticated(): Boolean = authenticated
    }

    private class Fixture(
        connected: Boolean = true,
        authenticated: Boolean = true,
        threadStarted: Boolean = true,
        socketClosed: Boolean = false,
    ) : AutoCloseable {
        val ssh = FakeSsh(connected, authenticated)
        val serverSocket = ServerSocket(0)
        val localPort = serverSocket.localPort
        private val release = CountDownLatch(1)
        private val thread = Thread({ runCatching { release.await() } }, "ssh-tunnel-test")
        val tunnel: SshTunnel

        init {
            if (socketClosed) serverSocket.close()
            val forwarder = LocalPortForwarder(
                ssh.getConnection(),
                Parameters("127.0.0.1", localPort, "remote.example", 22),
                serverSocket,
                LoggerFactory.DEFAULT,
            )
            if (threadStarted) thread.start()
            tunnel = SshTunnel(ssh, forwarder, serverSocket, thread, localPort)
        }

        override fun close() {
            release.countDown()
            runCatching { thread.join(1000) }
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun reportsRunningWhenThreadSocketAndSshTransportAreAllLive() {
        Fixture().use { assertTrue(it.tunnel.isRunning()) }
    }

    @Test
    fun reportsStoppedWhenSshLinkIsDisconnected() {
        Fixture(connected = false).use { assertFalse(it.tunnel.isRunning()) }
    }

    @Test
    fun reportsStoppedWhenSshLinkIsNotAuthenticated() {
        Fixture(authenticated = false).use { assertFalse(it.tunnel.isRunning()) }
    }

    @Test
    fun reportsStoppedWhenLoopbackSocketIsClosed() {
        Fixture(socketClosed = true).use { assertFalse(it.tunnel.isRunning()) }
    }

    @Test
    fun reportsStoppedWhenListenerThreadIsNotAlive() {
        Fixture(threadStarted = false).use { assertFalse(it.tunnel.isRunning()) }
    }
}
