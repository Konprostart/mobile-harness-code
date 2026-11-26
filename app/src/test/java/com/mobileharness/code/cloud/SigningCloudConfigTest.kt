package com.mobileharness.code.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningCloudConfigTest {

    @Test
    fun roundTripsAllFields() {
        val config = SigningCloudConfig(
            enabled = true,
            providerId = "saf",
            connection = "content://tree/abc",
            keepVersions = 7,
            passphraseEnc = "aesk1:opaque",
            lastBackupAt = 123456789L,
            lastBackupKeyCount = 3,
            lastBackupFingerprints = listOf("aa", "bb"),
            lastBackupError = "boom",
            lastRestoreAt = 987654321L,
        )
        val decoded = SigningCloudConfig.fromJson(SigningCloudConfig.toJson(config))
        assertEquals(config, decoded)
    }

    @Test
    fun defaultsAreSafeWhenJsonIsEmpty() {
        assertEquals(SigningCloudConfig(), SigningCloudConfig.fromJson(""))
        assertEquals(SigningCloudConfig(), SigningCloudConfig.fromJson("not json"))
    }

    @Test
    fun clampsKeepVersionsToBounds() {
        val low = SigningCloudConfig.fromJson(SigningCloudConfig.toJson(SigningCloudConfig(keepVersions = 0)))
        assertEquals(SigningCloudConfig.MIN_KEEP_VERSIONS, low.keepVersions)
        val high = SigningCloudConfig.fromJson(SigningCloudConfig.toJson(SigningCloudConfig(keepVersions = 999)))
        assertEquals(SigningCloudConfig.MAX_KEEP_VERSIONS, high.keepVersions)
    }

    @Test
    fun passphraseConfiguredReflectsTokenPresence() {
        assertFalse(SigningCloudConfig().passphraseConfigured)
        assertTrue(SigningCloudConfig(passphraseEnc = "x").passphraseConfigured)
    }
}
