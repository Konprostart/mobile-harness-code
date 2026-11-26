package com.mobileharness.code.cloud

import com.mobileharness.code.signing.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalDirectoryStorageProviderTest {

    @Test
    fun uploadDownloadRoundTripsBytes() {
        val root = Fixtures.tempDir("ldsp")
        val provider = LocalDirectoryStorageProvider(root)
        provider.connect()
        val bytes = "hello cloud".toByteArray(Charsets.UTF_8)
        provider.upload("signing/backup-1.sgnbak", bytes)
        assertTrue(provider.exists("signing/backup-1.sgnbak"))
        assertArrayEquals(bytes, provider.download("signing/backup-1.sgnbak"))
    }

    @Test
    fun listFiltersByPrefixAndSorts() {
        val root = Fixtures.tempDir("ldsp-list")
        val provider = LocalDirectoryStorageProvider(root)
        provider.connect()
        provider.upload("signing/b.sgnbak", byteArrayOf(1))
        provider.upload("signing/a.sgnbak", byteArrayOf(2))
        provider.upload("other/c.sgnbak", byteArrayOf(3))
        val names = provider.list("signing/").map { it.name }
        assertEquals(listOf("signing/a.sgnbak", "signing/b.sgnbak"), names)
    }

    @Test
    fun deleteAndMissingThrow() {
        val root = Fixtures.tempDir("ldsp-del")
        val provider = LocalDirectoryStorageProvider(root)
        provider.connect()
        provider.upload("signing/x.sgnbak", byteArrayOf(1))
        provider.delete("signing/x.sgnbak")
        assertFalse(provider.exists("signing/x.sgnbak"))
        try {
            provider.download("signing/x.sgnbak")
            fail("expected CloudNotFoundException")
        } catch (_: CloudNotFoundException) {
            // expected
        }
    }

    @Test
    fun operationsBeforeConnectThrow() {
        val provider = LocalDirectoryStorageProvider(Fixtures.tempDir("ldsp-nc"))
        try {
            provider.list("")
            fail("expected CloudNotConnectedException")
        } catch (_: CloudNotConnectedException) {
            // expected
        }
    }

    @Test
    fun rejectsUnsafeObjectNames() {
        val root = Fixtures.tempDir("ldsp-safe")
        val provider = LocalDirectoryStorageProvider(root)
        provider.connect()
        try {
            provider.upload("../escape.sgnbak", byteArrayOf(1))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
