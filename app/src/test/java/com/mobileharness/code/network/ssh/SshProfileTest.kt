package com.mobileharness.code.network.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileTest {
    private fun valid() = SshProfile(
        id = "a",
        name = "My server",
        host = "203.0.113.10",
        port = 22,
        username = "deploy",
    )

    @Test
    fun validProfileHasNoProblems() {
        assertTrue(valid().isValid())
        assertEquals(emptyList<String>(), valid().validate())
    }

    @Test
    fun missingNameHostAndUsernameAreReported() {
        val problems = valid().copy(name = "", host = "  ", username = "").validate()
        assertTrue(problems.any { it.contains("name") })
        assertTrue(problems.any { it.contains("Host") })
        assertTrue(problems.any { it.contains("Username") })
    }

    @Test
    fun outOfRangePortsAreReported() {
        assertTrue(valid().copy(port = 0).validate().any { it.contains("Port") })
        assertTrue(valid().copy(port = 65536).validate().any { it.contains("Port") })
    }

    @Test
    fun tunnelValidationOnlyAppliesWhenEnabled() {
        assertTrue(valid().copy(tunnelEnabled = false, tunnelLocalPort = 0).isValid())
        assertTrue(valid().copy(tunnelEnabled = true, tunnelLocalPort = 0).validate().any { it.contains("Local port") })
        assertTrue(valid().copy(tunnelEnabled = true, tunnelRemoteHost = "").validate().any { it.contains("Remote host") })
        assertTrue(valid().copy(tunnelEnabled = true, tunnelRemotePort = 99999).validate().any { it.contains("Remote port") })
    }

    @Test
    fun codecRoundTripsAllFields() {
        val profile = SshProfile(
            id = "b",
            name = "Worker",
            host = "worker.internal",
            port = 2222,
            username = "ci",
            authType = SshAuthType.PRIVATE_KEY,
            passphraseRequired = true,
            tunnelEnabled = true,
            tunnelLocalPort = 18080,
            tunnelRemoteHost = "127.0.0.1",
            tunnelRemotePort = 8080,
        )
        val decoded = SshProfileCodec.fromJson(SshProfileCodec.toJson(listOf(profile)))
        assertEquals(listOf(profile), decoded)
    }

    @Test
    fun codecDefaultsUnknownAndMissingFields() {
        val json = """[{"id":"x","name":"n","host":"h","auth_type":"BOGUS","tunnel_enabled":true,"tunnel_local_port":0}]"""
        val decoded = SshProfileCodec.fromJson(json)
        assertEquals(1, decoded.size)
        assertEquals(SshAuthType.PASSWORD, decoded[0].authType)
        assertEquals(22, decoded[0].port)
        assertFalse(decoded[0].passphraseRequired)
    }

    @Test
    fun codecDropsMalformedAndBlankEntries() {
        assertEquals(emptyList<SshProfile>(), SshProfileCodec.fromJson(""))
        assertEquals(emptyList<SshProfile>(), SshProfileCodec.fromJson("not json"))
        // Missing id → dropped.
        assertEquals(emptyList<SshProfile>(), SshProfileCodec.fromJson("""[{"name":"no-id"}]"""))
    }
}
