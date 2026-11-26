package com.mobileharness.code.cloud

import com.mobileharness.code.model.AndroidTemplate
import com.mobileharness.code.model.Project
import com.mobileharness.code.model.ProjectType
import com.mobileharness.code.signing.BackupEntry
import com.mobileharness.code.signing.Fixtures
import com.mobileharness.code.signing.KeyRecord
import com.mobileharness.code.signing.KeyRepository
import com.mobileharness.code.signing.KeystoreTools
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectBackupManagerTest {

    private val passphrase = "project-passphrase-123".toCharArray()

    private fun addKey(repo: KeyRepository, alias: String = "projectkey"): KeyRecord = repo.createGenerated(
        KeystoreTools.KeyGenSpec(
            alias = alias,
            password = "key-password-123".toCharArray(),
            keyAlgorithm = "RSA",
            keySize = 2048,
            validityDays = 365,
            commonName = "Project Test",
            organization = "Test",
            countryCode = "RS",
        ),
        displayName = "Project Test Key",
    )

    private fun project(): Project = Project(
        name = "Demo App",
        description = "A demo project",
        language = "Kotlin",
        slug = "demo-app",
        type = ProjectType.ANDROID,
        androidTemplate = AndroidTemplate.COMPOSE,
        gitRemoteUrl = "https://github.com/owner/demo-app.git",
        signingKeyIds = listOf("key-binding-1"),
    )

    private fun sourceTree(dir: File) {
        dir.mkdirs()
        File(dir, "build.gradle.kts").writeText("plugins { id('com.android.application') }\n")
        File(dir, "src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}\n") }
        val git = File(dir, ".git")
        git.mkdirs()
        File(git, "config").writeText("[core]\n\trepositoryformatversion = 0\n")
        File(git, "HEAD").writeText("ref: refs/heads/main\n")
    }

    private fun entries(repo: KeyRepository, key: KeyRecord): List<BackupEntry> =
        repo.backupEntries(listOf(key))

    @Test
    fun backupAndRestorePreserveProjectKeysAndFingerprint() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("pbm-storage")).apply { connect() }
        val source = Fixtures.tempDir("pbm-src")
        sourceTree(source)

        val repo = KeyRepository(Fixtures.tempDir("pbm-vault"), Fixtures.FakeCipher("k1"))
        val key = addKey(repo)
        val keys = entries(repo, key)

        val manager = ProjectBackupManager { 1_000L }
        val outcome = manager.backup(storage, project(), source, keys, passphrase, 5, Fixtures.tempDir("pbm-tmp"))
        assertEquals(1, outcome.keyCount)
        assertEquals(listOf(key.fingerprintSha256), outcome.fingerprints)
        assertTrue(outcome.sourceFileCount >= 4)

        val target = Fixtures.tempDir("pbm-restored")
        val restored = manager.restore(storage, "demo-app", passphrase, target, Fixtures.tempDir("pbm-tmp2"))

        assertEquals("Demo App", restored.project.name)
        assertEquals("https://github.com/owner/demo-app.git", restored.project.gitRemoteUrl)
        assertEquals(listOf("key-binding-1"), restored.project.signingKeyIds)
        assertEquals(AndroidTemplate.COMPOSE, restored.project.androidTemplate)
        assertEquals(1, restored.keys.size)
        assertEquals(key.fingerprintSha256, restored.keys.single().fingerprintSha256)

        // Source files (including .git) restored byte-identically.
        assertEquals("fun main() {}\n", File(target, "src/Main.kt").readText())
        assertTrue(File(target, ".git/HEAD").isFile)
        assertArrayEquals(
            File(source, "build.gradle.kts").readBytes(),
            File(target, "build.gradle.kts").readBytes(),
        )
    }

    @Test
    fun keepsOnlyLatestVersionsPerProject() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("pbm-ver-storage")).apply { connect() }
        val source = Fixtures.tempDir("pbm-ver-src"); sourceTree(source)
        val repo = KeyRepository(Fixtures.tempDir("pbm-ver-vault"), Fixtures.FakeCipher("k1"))
        val keys = entries(repo, addKey(repo))
        var now = 1_000L
        val manager = ProjectBackupManager { now }
        repeat(6) {
            manager.backup(storage, project(), source, keys, passphrase, 3, Fixtures.tempDir("pbm-ver-tmp"))
            now += 1_000L
        }
        assertEquals(3, manager.listBackups(storage, "demo-app").size)
        val newest = manager.listBackups(storage, "demo-app").first()
        assertEquals("projects/demo-app/backup-6000.mhproj", newest.name)
    }

    @Test
    fun projectsDoNotCollideInSharedStorage() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("pbm-collide-storage")).apply { connect() }
        val source = Fixtures.tempDir("pbm-collide-src"); sourceTree(source)
        val repo = KeyRepository(Fixtures.tempDir("pbm-collide-vault"), Fixtures.FakeCipher("k1"))
        val keys = entries(repo, addKey(repo))
        val manager = ProjectBackupManager { 5_000L }
        manager.backup(storage, project(), source, keys, passphrase, 5, Fixtures.tempDir("pbm-collide-tmp"))
        manager.backup(storage, project().copy(name = "Other", slug = "other-app"), source, keys, passphrase, 5, Fixtures.tempDir("pbm-collide-tmp2"))
        assertEquals(1, manager.listBackups(storage, "demo-app").size)
        assertEquals(1, manager.listBackups(storage, "other-app").size)
    }

    @Test
    fun wrongPassphraseFails() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("pbm-wp-storage")).apply { connect() }
        val source = Fixtures.tempDir("pbm-wp-src"); sourceTree(source)
        val repo = KeyRepository(Fixtures.tempDir("pbm-wp-vault"), Fixtures.FakeCipher("k1"))
        val keys = entries(repo, addKey(repo))
        ProjectBackupManager().backup(storage, project(), source, keys, passphrase, 5, Fixtures.tempDir("pbm-wp-tmp"))
        val target = Fixtures.tempDir("pbm-wp-restored")
        try {
            ProjectBackupManager().restore(storage, "demo-app", "wrong-passphrase".toCharArray(), target, Fixtures.tempDir("pbm-wp-tmp2"))
            fail("expected BackupException")
        } catch (_: com.mobileharness.code.signing.BackupException) {
            // expected
        }
        assertTrue(target.listFiles().isNullOrEmpty())
    }

    @Test
    fun restoreWithNoBackupFails() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("pbm-nb-storage")).apply { connect() }
        try {
            ProjectBackupManager().restore(storage, "demo-app", passphrase, Fixtures.tempDir("pbm-nb-rest"), Fixtures.tempDir("pbm-nb-tmp"))
            fail("expected BackupException")
        } catch (_: com.mobileharness.code.signing.BackupException) {
            // expected
        }
    }

    @Test
    fun backupRequiresAtLeastOneKey() {
        val storage = LocalDirectoryStorageProvider(Fixtures.tempDir("pbm-nokey-storage")).apply { connect() }
        val source = Fixtures.tempDir("pbm-nokey-src"); sourceTree(source)
        try {
            ProjectBackupManager().backup(storage, project(), source, emptyList(), passphrase, 5, Fixtures.tempDir("pbm-nokey-tmp"))
            fail("expected BackupException")
        } catch (_: com.mobileharness.code.signing.BackupException) {
            // expected
        }
    }

    @Test
    fun providerUploadErrorPropagates() {
        val source = Fixtures.tempDir("pbm-err-src"); sourceTree(source)
        val repo = KeyRepository(Fixtures.tempDir("pbm-err-vault"), Fixtures.FakeCipher("k1"))
        val keys = entries(repo, addKey(repo))
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
        try {
            ProjectBackupManager().backup(failing, project(), source, keys, passphrase, 5, Fixtures.tempDir("pbm-err-tmp"))
            fail("expected CloudNetworkException")
        } catch (_: CloudNetworkException) {
            // expected
        }
    }
}
