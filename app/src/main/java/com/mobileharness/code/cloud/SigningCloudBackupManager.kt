package com.mobileharness.code.cloud

import com.mobileharness.code.signing.BackupException
import com.mobileharness.code.signing.BackupManager
import com.mobileharness.code.signing.BackupSummary
import com.mobileharness.code.signing.KeyRepository
import java.io.File

/** Result of a successful cloud backup. */
data class SigningBackupOutcome(
    val uploadedName: String,
    val keyCount: Int,
    val fingerprints: List<String>,
    val pruned: Int,
    val timestamp: Long,
)

/** Result of a successful cloud restore. */
data class SigningRestoreOutcome(
    val restored: Int,
    val fingerprints: List<String>,
)

/**
 * Ties the Signiffia keystore vault, the encrypted `.sgnbak` container and a pluggable
 * [CloudStorageProvider] together.
 *
 * - [backup] builds one `.sgnbak` from every vault key (keystore bytes + passwords, AES-256-GCM
 *   encrypted with [passphrase]), uploads it and prunes older versions so only [keepVersions] remain.
 *   Only the encrypted container is ever sent to the provider.
 * - [restore] downloads the newest backup, validates every keystore against its recorded certificate
 *   fingerprint and re-registers it in the vault.
 *
 * The class is plain JVM and provider agnostic; it never generates, replaces or rewrites a key.
 */
class SigningCloudBackupManager(
    private val keyRepository: KeyRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun backup(
        provider: CloudStorageProvider,
        passphrase: CharArray,
        keepVersions: Int,
        tempDir: File,
    ): SigningBackupOutcome {
        val entries = keyRepository.backupEntries(keyRepository.list())
        if (entries.isEmpty()) throw BackupException("Nema ključeva za backup.")
        val timestamp = clock()
        val name = "$BACKUP_PREFIX${fileNameFor(timestamp)}"
        val bytes = buildArchive(passphrase, entries, tempDir)
        provider.upload(name, bytes)
        val pruned = prune(provider, keepVersions)
        return SigningBackupOutcome(name, entries.size, entries.map { it.fingerprintSha256 }, pruned, timestamp)
    }

    fun restore(
        provider: CloudStorageProvider,
        passphrase: CharArray,
        tempDir: File,
    ): SigningRestoreOutcome {
        val backups = listBackups(provider)
        if (backups.isEmpty()) throw BackupException("U skladištu nema sačuvanih backup-a.")
        val newest = backups.first()
        val summary = readArchive(provider.download(newest.name), passphrase, tempDir)
        val restored = keyRepository.restore(summary.keys)
        // KeyRepository.restore already verifies each fingerprint; re-confirm it matches here so the
        // caller can show the user the exact certificate that was restored.
        val fingerprints = restored.map { it.fingerprintSha256 }
        return SigningRestoreOutcome(restored.size, fingerprints)
    }

    /** All `.sgnbak` objects under the signing prefix, newest first. */
    fun listBackups(provider: CloudStorageProvider): List<CloudObject> =
        provider.list(BACKUP_PREFIX)
            .filter { it.name.endsWith(BACKUP_SUFFIX) }
            .sortedByDescending { it.name }

    /** Removes everything beyond the newest [keepVersions]; returns how many were removed. */
    fun prune(provider: CloudStorageProvider, keepVersions: Int): Int {
        val backups = listBackups(provider)
        if (backups.size <= keepVersions) return 0
        var removed = 0
        for (object_ in backups.drop(keepVersions)) {
            provider.delete(object_.name)
            removed++
        }
        return removed
    }

    // ---------------------------------------------------------------- internals

    private fun fileNameFor(timestamp: Long): String = "backup-$timestamp$BACKUP_SUFFIX"

    private fun buildArchive(passphrase: CharArray, entries: List<com.mobileharness.code.signing.BackupEntry>, tempDir: File): ByteArray {
        tempDir.mkdirs()
        val file = File(tempDir, "cloud-backup-${System.nanoTime()}$BACKUP_SUFFIX")
        return try {
            BackupManager.write(file, passphrase, entries, verifyAfterWrite = true)
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun readArchive(bytes: ByteArray, passphrase: CharArray, tempDir: File): BackupSummary {
        tempDir.mkdirs()
        val file = File(tempDir, "cloud-restore-${System.nanoTime()}$BACKUP_SUFFIX")
        return try {
            file.writeBytes(bytes)
            BackupManager.read(file, passphrase, verifyKeys = true, tempDir = tempDir)
        } finally {
            file.delete()
        }
    }

    companion object {
        const val BACKUP_PREFIX = "signing/"
        private const val BACKUP_SUFFIX = ".sgnbak"
    }
}
