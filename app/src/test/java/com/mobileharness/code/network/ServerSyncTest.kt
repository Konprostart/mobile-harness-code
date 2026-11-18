package com.mobileharness.code.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSyncTest {
    @Test
    fun disabledConfigAlwaysReportsDisabled() {
        val config = ServerSyncConfig(enabled = false)
        assertEquals(listOf("Sync is disabled"), config.validate())
    }

    @Test
    fun sshConfigValidatesRequiredFields() {
        val errors = ServerSyncConfig(enabled = true, mode = ServerSyncMode.SSH).validate()
        assertTrue(errors.any { it.contains("host") })
        assertTrue(errors.any { it.contains("directory") })
        assertTrue(ServerSyncConfig(
            enabled = true,
            mode = ServerSyncMode.SSH,
            sshHost = "example.com",
            sshRemoteDir = "/backups",
        ).validate().isEmpty())
    }

    @Test
    fun dukoiConfigRequiresScheme() {
        val errors = ServerSyncConfig(enabled = true, mode = ServerSyncMode.DUKOI, dukoiBaseUrl = "127.0.0.1:8080").validate()
        assertTrue(errors.any { it.contains("scheme") })
        assertTrue(ServerSyncConfig(
            enabled = true,
            mode = ServerSyncMode.DUKOI,
            dukoiBaseUrl = "https://dukoi.com",
        ).validate().isEmpty())
    }

    @Test
    fun sshPushArgsUsePortAndRemoteDirectory() {
        val config = ServerSyncConfig(
            enabled = true,
            mode = ServerSyncMode.SSH,
            sshHost = "backup.example.com",
            sshPort = 2222,
            sshUser = "deploy",
            sshKeyPath = "/root/.ssh/id_ed25519",
            sshRemoteDir = "/backups/",
        )
        val args = SshSyncCommands.pushArgs(config, "/tmp/b.mhbackup", "b.mhbackup")
        assertEquals("deploy@backup.example.com:/backups/b.mhbackup", args.last())
        assertTrue(args.contains("2222"))
        assertTrue(args.contains("/root/.ssh/id_ed25519"))
        assertTrue(args.contains("StrictHostKeyChecking=accept-new"))
    }

    @Test
    fun dukoiEndpointsBuildExpectedUrlsAndAuth() {
        val config = ServerSyncConfig(
            enabled = true,
            mode = ServerSyncMode.DUKOI,
            dukoiBaseUrl = "https://dukoi.com/",
            dukoiToken = "secret-token",
        )
        assertEquals("https://dukoi.com/sync?name=b.mhbackup", DukoiSyncEndpoints.pushUrl(config, "b.mhbackup"))
        assertEquals("https://dukoi.com/sync/latest", DukoiSyncEndpoints.latestUrl(config))
        assertEquals(mapOf("Authorization" to "Bearer secret-token"), DukoiSyncEndpoints.authorizationHeader(config))
        assertEquals(emptyMap<String, String>(), DukoiSyncEndpoints.authorizationHeader(config.copy(dukoiToken = "")))
    }
}
