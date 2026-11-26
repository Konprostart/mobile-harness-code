package com.mobileharness.code.cloud

import com.mobileharness.code.signing.BackupException
import com.mobileharness.code.signing.Fixtures
import com.mobileharness.code.signing.KeyRecord
import com.mobileharness.code.signing.KeyRepository
import com.mobileharness.code.signing.KeystoreTools
import com.mobileharness.code.signing.SecretCipher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SigningCloudBackupManagerTest {

    private val passphrase = "cloud-passphrase-123".toCharArray()

    private fun addKey(repo: KeyRepository): KeyRecord = repo.createGenerated(
        KeystoreTools.KeyGenSpec(
            alias = "testkey",
            password = "test-password-123".toCharArray(),
            keyAlgorithm = "RSA",
            keySize = 2048,
            validityDays = 365,
            commonName = "Cloud Test",
            organization = "Test",
            countryCode = "RS",
        ),
        displayName = "Cloud Test Key",
    )

    private fun repo(dir: File, cipher: SecretCipher): KeyRepository = KeyRepository(dir, cipher)

    @Test
    fun backupAndRestorePreserveFingerprintAcrossDevices() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("scb-storage")).apply { connect() }

        val repo1 = repo(Fixtures.tempDir("scb-a"), Fixtures.FakeCipher("k1"))
        val key1 = addKey(repo1)
        val manager1 = SigningCloudBackupManager(repo1)
        val outcome = manager1.backup(storage, passphrase, 5, Fixtures.tempDir("scb-tmp-a"))
        assertEquals(1, outcome.keyCount)
        assertEquals(listOf(key1.fingerprintSha256), outcome.fingerprints)

        // "New device": a different device-bound cipher key, empty vault.
        val repo2 = repo(Fixtures.tempDir("scb-b"), Fixtures.FakeCipher("k2"))
        val manager2 = SigningCloudBackupManager(repo2)
        val restored = manager2.restore(storage, passphrase, Fixtures.tempDir("scb-tmp-b"))

        assertEquals(1, restored.restored)
        assertEquals(outcome.fingerprints, restored.fingerprints)
        assertEquals(key1.fingerprintSha256, repo2.list().single().fingerprintSha256)
    }

    @Test
    fun keepsOnlyLatestVersions() {
        val repo = repo(Fixtures.tempDir("scb-vault"), Fixtures.FakeCipher("k1"))
        addKey(repo)
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("scb-ver-storage")).apply { connect() }
        var now = 1_000L
        val manager = SigningCloudBackupManager(repo) { now }
        repeat(7) {
            manager.backup(storage, passphrase, 3, Fixtures.tempDir("scb-ver-tmp"))
            now += 1_000L
        }
        assertEquals(3, manager.listBackups(storage).size)
        // Newest first: the last backup ran with timestamp 7000.
        val newest = manager.listBackups(storage).first()
        assertEquals("signing/backup-7000.sgnbak", newest.name)
    }

    @Test
    fun propagatesProviderUploadErrors() {
        val repo = repo(Fixtures.tempDir("scb-err"), Fixtures.FakeCipher("k1"))
        addKey(repo)
        val failing = object : CloudStorageProvider {
            override val id: String = "failing"
            override val displayName: String = "Failing"
            override val isConnected: Boolean = true
            override fun connect() {}
            override fun disconnect() {}
            override fun list(prefix: String): List<CloudObject> = emptyList()
            override fun upload(name: String, bytes: ByteArray) { throw CloudNetworkException("sync failed") }
            override fun download(name: String): ByteArray = byteArrayOf()
            override fun delete(name: String) {}
            override fun exists(name: String): Boolean = false
        }
        val manager = SigningCloudBackupManager(repo)
        try {
            manager.backup(failing, passphrase, 5, Fixtures.tempDir("scb-err-tmp"))
            fail("expected CloudNetworkException")
        } catch (_: CloudNetworkException) {
            // expected
        }
    }

    @Test
    fun restoreWithWrongPassphraseFails() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("scb-wp-storage")).apply { connect() }
        val repo1 = repo(Fixtures.tempDir("scb-wp-a"), Fixtures.FakeCipher("k1"))
        addKey(repo1)
        SigningCloudBackupManager(repo1).backup(storage, passphrase, 5, Fixtures.tempDir("scb-wp-tmp"))

        val repo2 = repo(Fixtures.tempDir("scb-wp-b"), Fixtures.FakeCipher("k2"))
        try {
            SigningCloudBackupManager(repo2).restore(storage, "wrong-passphrase".toCharArray(), Fixtures.tempDir("scb-wp-tmp2"))
            fail("expected BackupException")
        } catch (_: BackupException) {
            // expected
        }
        assertEquals(0, repo2.list().size)
    }

    @Test
    fun backupWithNoKeysFails() {
        val repo = repo(Fixtures.tempDir("scb-empty"), Fixtures.FakeCipher("k1"))
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("scb-empty-storage")).apply { connect() }
        try {
            SigningCloudBackupManager(repo).backup(storage, passphrase, 5, Fixtures.tempDir("scb-empty-tmp"))
            fail("expected BackupException")
        } catch (_: BackupException) {
            // expected
        }
    }

    @Test
    fun restoreWithNoBackupsFails() {
        val repo = repo(Fixtures.tempDir("scb-nb"), Fixtures.FakeCipher("k1"))
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("scb-nb-storage")).apply { connect() }
        try {
            SigningCloudBackupManager(repo).restore(storage, passphrase, Fixtures.tempDir("scb-nb-tmp"))
            fail("expected BackupException")
        } catch (_: BackupException) {
            // expected
        }
    }

    @Test
    fun backupRequiresConnectedProvider() {
        val repo = repo(Fixtures.tempDir("scb-nc"), Fixtures.FakeCipher("k1"))
        addKey(repo)
        val notConnected = LocalDirectoryStorageProvider(Fixtures.tempDir("scb-nc-storage"))
        try {
            SigningCloudBackupManager(repo).backup(notConnected, passphrase, 5, Fixtures.tempDir("scb-nc-tmp"))
            fail("expected CloudNotConnectedException")
        } catch (_: CloudNotConnectedException) {
            // expected
        }
    }
}
