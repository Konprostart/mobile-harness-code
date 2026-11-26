package com.mobileharness.code.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectCloudBackupTest {

    @Test
    fun roundTripsAllFields() {
        val config = ProjectCloudBackup(
            enabled = true,
            providerId = "saf",
            connection = "content://tree/abc",
            keepVersions = 7,
            passphraseEnc = "enc-passphrase",
            autoBackup = true,
            lastBackupAt = 12345L,
            lastBackupFingerprints = listOf("aa", "bb"),
            lastBackupError = "",
            lastRestoreAt = 999L,
        )
        val json = ProjectCloudBackup.toJson(config)
        assertEquals(config, ProjectCloudBackup.fromJson(json))
    }

    @Test
    fun defaultsAndClamps() {
        assertEquals(ProjectCloudBackup(), ProjectCloudBackup.fromJson("{}"))
        val clamped = ProjectCloudBackup.fromJson("""{"keepVersions": 999}""")
        assertEquals(SigningCloudConfig.MAX_KEEP_VERSIONS, clamped.keepVersions)
        val min = ProjectCloudBackup.fromJson("""{"keepVersions": 0}""")
        assertEquals(SigningCloudConfig.MIN_KEEP_VERSIONS, min.keepVersions)
    }

    @Test
    fun storeMapRoundTripAndRemoval() {
        val a = ProjectCloudBackup(enabled = true, providerId = "local", lastBackupAt = 1L)
        val b = ProjectCloudBackup(enabled = false)
        val json = ProjectCloudBackupStore.encode(mapOf("p1" to a, "p2" to b))
        val decoded = ProjectCloudBackupStore.decode(json)
        assertEquals(a, decoded["p1"])
        assertEquals(b, decoded["p2"])

        val afterPut = ProjectCloudBackupStore.put(json, "p1", ProjectCloudBackup())
        assertFalse(ProjectCloudBackupStore.decode(afterPut).containsKey("p1"))

        val removed = ProjectCloudBackupStore.remove(afterPut, "p2")
        assertTrue(ProjectCloudBackupStore.decode(removed).isEmpty())
    }

    @Test
    fun getMissingReturnsDefault() {
        assertEquals(ProjectCloudBackup(), ProjectCloudBackupStore.get("{}", "unknown"))
    }
}
