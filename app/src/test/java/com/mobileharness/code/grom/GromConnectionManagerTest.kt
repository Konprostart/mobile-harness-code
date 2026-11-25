package com.mobileharness.code.grom

import app.grom.sdk.connect.GromConnectClient
import app.grom.sdk.connect.GromTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GromConnectionManagerTest {

    private fun manager(
        plane: FakeGromDataPlane,
        config: GromConfig = GromConfig(),
        secrets: MemorySecretStore = MemorySecretStore(),
        transport: GromTransport = FakeGromTransport(plane),
    ) = GromConnectionManager(
        configStore = MemoryConfigStore(config),
        secretStore = secrets,
        clientFactory = { url -> GromConnectClient(url, transport = transport) },
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun offByDefaultAndConnectRefusesToRun() = runBlocking {
        val m = manager(FakeGromDataPlane())
        assertEquals(GromPhase.OFF, m.snapshot.value.phase)
        val result = m.connect()
        assertEquals(GromPhase.OFF, result.phase)
    }

    @Test
    fun passwordFlowReachesConnectedWithServices() = runBlocking {
        val secrets = MemorySecretStore()
        val m = manager(FakeGromDataPlane(), secrets = secrets)
        m.savePassword("secret")
        m.setEnabled(true)

        val result = m.connect()

        assertEquals(GromPhase.CONNECTED, result.phase)
        assertEquals("0.16.0-server29", result.serverVersion)
        assertEquals(1, result.apiVersion)
        assertEquals("peer", result.principal)
        assertTrue(result.capabilities.contains("services.read"))
        assertEquals(listOf("agent", "llm"), result.services.map { it.id })
    }

    @Test
    fun invalidPasswordEndsInAuthRequired() = runBlocking {
        val secrets = MemorySecretStore()
        val plane = FakeGromDataPlane(rejectLogin = true)
        val m = manager(plane, secrets = secrets)
        m.savePassword("wrong")
        m.setEnabled(true)

        val result = m.connect()

        assertEquals(GromPhase.AUTH_REQUIRED, result.phase)
        assertNotNull(result.message)
    }

    @Test
    fun missingPasswordEndsInAuthRequired() = runBlocking {
        val m = manager(FakeGromDataPlane())
        m.setEnabled(true)

        val result = m.connect()

        assertEquals(GromPhase.AUTH_REQUIRED, result.phase)
    }

    @Test
    fun incompatibleApiIsReportedNotGuessed() = runBlocking {
        val plane = FakeGromDataPlane(apiVersion = 2)
        val secrets = MemorySecretStore()
        val m = manager(plane, secrets = secrets)
        m.savePassword("secret")
        m.setEnabled(true)

        val result = m.connect()

        assertEquals(GromPhase.INCOMPATIBLE, result.phase)
        assertNotNull(result.message)
    }

    @Test
    fun transportFailureIsDisconnected() = runBlocking {
        val throwing = GromTransport { throw java.io.IOException("boom") }
        val secrets = MemorySecretStore()
        val m = manager(FakeGromDataPlane(), secrets = secrets, transport = throwing)
        m.savePassword("secret")
        m.setEnabled(true)

        val result = m.connect()

        assertEquals(GromPhase.DISCONNECTED, result.phase)
    }

    @Test
    fun keyAuthReachesConnected() = runBlocking {
        val secrets = MemorySecretStore()
        val m = manager(FakeGromDataPlane(), secrets = secrets)
        val identity = m.generateKey()
        assertNotNull(identity)
        m.setAuthMethod(GromConfig.AUTH_KEY)
        m.setEnabled(true)

        val result = m.connect()

        assertEquals(GromPhase.CONNECTED, result.phase)
    }

    @Test
    fun rpcCallWorksAfterConnect() = runBlocking {
        val secrets = MemorySecretStore()
        val m = manager(FakeGromDataPlane(), secrets = secrets)
        m.savePassword("secret")
        m.setEnabled(true)
        m.connect()

        val result = m.call("agent", "kv.put", mapOf("key" to "a", "value" to "b"))

        assertEquals("agent", result.plugin)
        assertEquals("kv.put", result.op)
        assertEquals(true, result.result["stored"])
    }

    @Test
    fun disconnectKeepsConfigAndCredentialButDropsSession() = runBlocking {
        val secrets = MemorySecretStore()
        val m = manager(FakeGromDataPlane(), secrets = secrets)
        m.savePassword("secret")
        m.setEnabled(true)
        m.connect()
        assertEquals(GromPhase.CONNECTED, m.snapshot.value.phase)

        m.disconnect()

        assertEquals(GromPhase.DISCONNECTED, m.snapshot.value.phase)
        assertTrue(m.config().enabled)
        assertTrue(m.hasPassword())
    }

    @Test
    fun turningOffResetsToOffAndDoesNotTouchServerConfig() = runBlocking {
        val secrets = MemorySecretStore()
        val m = manager(FakeGromDataPlane(), secrets = secrets)
        m.savePassword("secret")
        m.setEnabled(true)
        m.connect()

        m.setEnabled(false)

        assertEquals(GromPhase.OFF, m.snapshot.value.phase)
        assertFalse(m.config().enabled)
        // The saved address and password are preserved for the next enable.
        assertTrue(m.hasPassword())
        assertEquals(GromConfig.DEFAULT_BASE_URL, m.config().baseUrl)
    }

    @Test
    fun reconnectStopsAtAuthFailureInsteadOfLooping() = runBlocking {
        val secrets = MemorySecretStore()
        val m = manager(FakeGromDataPlane(rejectLogin = true), secrets = secrets)
        m.savePassword("wrong")
        m.setEnabled(true)

        val result = m.reconnect(maxAttempts = 5, baseDelayMs = 1)

        assertEquals(GromPhase.AUTH_REQUIRED, result.phase)
    }
}
