package com.mobileharness.code.cloud

import com.mobileharness.code.backup.BackupCrypto
import com.mobileharness.code.backup.WrongBackupPasswordException
import com.mobileharness.code.model.AndroidTemplate
import com.mobileharness.code.model.Project
import com.mobileharness.code.model.ProjectKind
import com.mobileharness.code.model.ProjectType
import com.mobileharness.code.signing.BackupEntry
import com.mobileharness.code.signing.BackupException
import com.mobileharness.code.signing.BackupManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Result of a successful per-project cloud backup. */
data class ProjectBackupOutcome(
    val uploadedName: String,
    val sourceFileCount: Int,
    val keyCount: Int,
    val fingerprints: List<String>,
    val pruned: Int,
    val timestamp: Long,
)

/** Result of a successful per-project cloud restore. */
data class ProjectRestoreOutcome(
    val project: Project,
    val keys: List<BackupEntry>,
    val sourceFileCount: Int,
)

/**
 * Per-project backup: source code (including `.git`), project metadata and the project's bound
 * signing keys, encrypted in a single pass with [BackupCrypto] (the same segment AES-256-GCM +
 * PBKDF2 format the whole-workspace `.mhbackup` uses).
 *
 * Layout inside the encrypted ZIP:
 *  - `manifest.properties` — project metadata (name, slug, kind, type, git remote, bound key ids).
 *  - `keys.zip` — the Signiffia key archive (keystore bytes + passwords, [BackupManager] manifest).
 *  - `source/<relative path>` — the workspace files, `.git` included.
 *
 * Only the encrypted container ever reaches the provider. A restore validates every keystore against
 * its recorded certificate fingerprint before returning it, so the caller can register it safely.
 *
 * The class is plain JVM and provider agnostic; it never generates, replaces or rewrites a key.
 */
class ProjectBackupManager(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun backup(
        provider: CloudStorageProvider,
        project: Project,
        sourceRoot: File,
        keyEntries: List<BackupEntry>,
        passphrase: CharArray,
        keepVersions: Int,
        tempDir: File,
    ): ProjectBackupOutcome {
        requireMinPassphrase(passphrase)
        if (keyEntries.isEmpty()) throw BackupException("Projekat nema sačuvan signing ključ za backup.")
        val timestamp = clock()
        val name = "$PREFIX${project.slug}/backup-${timestamp}$SUFFIX"
        val bytes = buildContainer(project, sourceRoot, keyEntries, passphrase, tempDir)
        provider.upload(name, bytes)
        val pruned = prune(provider, project.slug, keepVersions)
        return ProjectBackupOutcome(
            uploadedName = name,
            sourceFileCount = countSourceFiles(sourceRoot),
            keyCount = keyEntries.size,
            fingerprints = keyEntries.map { it.fingerprintSha256 },
            pruned = pruned,
            timestamp = timestamp,
        )
    }

    fun restore(
        provider: CloudStorageProvider,
        slug: String,
        passphrase: CharArray,
        targetDir: File,
        tempDir: File,
    ): ProjectRestoreOutcome {
        val backups = listBackups(provider, slug)
        if (backups.isEmpty()) throw BackupException("U skladištu nema sačuvanih backup-a za ovaj projekat.")
        val newest = backups.first()
        val bytes = provider.download(newest.name)
        tempDir.mkdirs()
        val file = File(tempDir, "project-restore-${System.nanoTime()}$SUFFIX")
        return try {
            file.writeBytes(bytes)
            readContainer(file, passphrase, targetDir, tempDir, writeSource = true)
        } finally {
            file.delete()
        }
    }

    /** Downloads and validates only the signing keys (source files are not written). */
    fun restoreKeysOnly(
        provider: CloudStorageProvider,
        slug: String,
        passphrase: CharArray,
        tempDir: File,
    ): List<BackupEntry> {
        val backups = listBackups(provider, slug)
        if (backups.isEmpty()) throw BackupException("U skladištu nema sačuvanih backup-a za ovaj projekat.")
        val newest = backups.first()
        val bytes = provider.download(newest.name)
        tempDir.mkdirs()
        val file = File(tempDir, "project-keys-${System.nanoTime()}$SUFFIX")
        return try {
            file.writeBytes(bytes)
            readContainer(file, passphrase, File(tempDir, "discard-${System.nanoTime()}"), tempDir, writeSource = false).keys
        } finally {
            file.delete()
        }
    }

    /** All project backups under a slug, newest first. */
    fun listBackups(provider: CloudStorageProvider, slug: String): List<CloudObject> =
        provider.list("$PREFIX$slug/")
            .filter { it.name.endsWith(SUFFIX) }
            .sortedByDescending { it.name }

    /** Distinct project slugs that have at least one backup in the storage. */
    fun listProjectSlugs(provider: CloudStorageProvider): List<String> =
        provider.list(PREFIX)
            .mapNotNull { object_ ->
                val rest = object_.name.removePrefix(PREFIX)
                rest.substringBefore('/').takeIf { it.isNotBlank() && object_.name.endsWith(SUFFIX) }
            }
            .distinct()
            .sorted()

    /** Removes everything beyond the newest [keepVersions]; returns how many were removed. */
    fun prune(provider: CloudStorageProvider, slug: String, keepVersions: Int): Int {
        val backups = listBackups(provider, slug)
        if (backups.size <= keepVersions) return 0
        var removed = 0
        for (object_ in backups.drop(keepVersions)) {
            provider.delete(object_.name)
            removed++
        }
        return removed
    }

    // ---------------------------------------------------------------- container

    private fun buildContainer(
        project: Project,
        sourceRoot: File,
        keyEntries: List<BackupEntry>,
        passphrase: CharArray,
        tempDir: File,
    ): ByteArray {
        tempDir.mkdirs()
        val file = File(tempDir, "project-backup-${System.nanoTime()}$SUFFIX")
        try {
            FileOutputStream(file).use { raw ->
                val encrypted = BackupCrypto.openWriter(raw, passphrase)
                ZipOutputStream(encrypted.buffered()).use { zip ->
                    writeManifest(zip, project, keyEntries.size)
                    writeKeys(zip, keyEntries)
                    writeSource(zip, sourceRoot)
                }
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun readContainer(
        file: File,
        passphrase: CharArray,
        targetDir: File,
        tempDir: File,
        writeSource: Boolean,
    ): ProjectRestoreOutcome {
        targetDir.mkdirs()
        var manifest: Properties? = null
        var keysBytes: ByteArray? = null
        var sourceFileCount = 0
        try {
            FileInputStream(file).use { raw ->
                val plain = try {
                    BackupCrypto.openReader(raw, passphrase)
                } catch (e: WrongBackupPasswordException) {
                    throw BackupException("Pogrešna backup lozinka ili je backup oštećen.", e)
                }
                ZipInputStream(plain.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    var entries = 0
                    while (entry != null) {
                        entries++
                        if (entries > MAX_ZIP_ENTRIES) throw BackupException("Backup sadrži previše stavki.")
                        val name = entry.name
                        requireSafeMemberName(name)
                        when {
                            name == MANIFEST -> manifest = Properties().apply { load(zip) }
                            name == KEYS_ZIP -> keysBytes = readBounded(zip, MAX_KEYS_ZIP_BYTES)
                            name.startsWith(SOURCE_PREFIX) -> {
                                if (writeSource) {
                                    writeSourceEntry(zip, targetDir, name.removePrefix(SOURCE_PREFIX))
                                }
                                sourceFileCount++
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Throwable) {
            throw BackupException("Backup nije moguće pročitati: ${e.message}", e)
        }
        val properties = manifest ?: throw BackupException("Backup nema manifest.")
        val keysArchive = keysBytes ?: throw BackupException("Backup nema ključeve.")
        val project = readProject(properties)
        // Fingerprint verification happens here; an invalid/corrupt key aborts the restore.
        val keys = BackupManager.decodeKeysArchive(keysArchive, verifyKeys = true, tempDir = tempDir)
        return ProjectRestoreOutcome(project, keys, sourceFileCount)
    }

    // ---------------------------------------------------------------- manifest

    private fun writeManifest(zip: ZipOutputStream, project: Project, keyCount: Int) {
        val properties = Properties()
        properties["format"] = FORMAT
        properties["version"] = VERSION.toString()
        properties["created"] = clock().toString()
        properties["project.id"] = project.id
        properties["project.name"] = b64(project.name)
        properties["project.description"] = b64(project.description)
        properties["project.language"] = b64(project.language)
        properties["project.slug"] = b64(project.slug)
        properties["project.rootPath"] = b64(project.rootPath)
        properties["project.kind"] = project.kind.name
        properties["project.type"] = project.type.name
        project.androidTemplate?.let { properties["project.androidTemplate"] = it.name }
        project.gitRemoteUrl?.let { properties["project.gitRemoteUrl"] = b64(it) }
        properties["project.signingKeyIds"] = b64(project.signingKeyIds.joinToString("\u0000"))
        properties["keyCount"] = keyCount.toString()
        zip.putNextEntry(ZipEntry(MANIFEST))
        ByteArrayOutputStream().use { properties.store(it, null); zip.write(it.toByteArray()) }
        zip.closeEntry()
    }

    private fun readProject(properties: Properties): Project {
        if (properties.getProperty("format") != FORMAT) throw BackupException("Manifest nije Mobile Harness project backup.")
        fun required(name: String): String = properties.getProperty(name)
            ?: throw BackupException("Manifest nema polje $name.")
        val signingKeyIds = unb64(properties.getProperty("project.signingKeyIds") ?: "")
            .split("\u0000").filter { it.isNotEmpty() }
        return Project(
            id = required("project.id"),
            name = unb64(required("project.name")),
            description = unb64(properties.getProperty("project.description") ?: ""),
            language = unb64(properties.getProperty("project.language") ?: ""),
            slug = unb64(properties.getProperty("project.slug") ?: projectSlug(unb64(required("project.name")))),
            rootPath = unb64(properties.getProperty("project.rootPath") ?: ""),
            updatedAtMillis = properties.getProperty("created")?.toLongOrNull() ?: clock(),
            kind = runCatching { ProjectKind.valueOf(required("project.kind")) }.getOrDefault(ProjectKind.PROJECT),
            type = runCatching { ProjectType.valueOf(properties.getProperty("project.type") ?: ProjectType.GENERAL.name) }
                .getOrDefault(ProjectType.GENERAL),
            androidTemplate = properties.getProperty("project.androidTemplate")?.let {
                runCatching { AndroidTemplate.valueOf(it) }.getOrNull()
            },
            gitRemoteUrl = unb64(properties.getProperty("project.gitRemoteUrl") ?: "").ifBlank { null },
            signingKeyIds = signingKeyIds,
        )
    }

    private fun projectSlug(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "project" }

    // ---------------------------------------------------------------- keys + source

    private fun writeKeys(zip: ZipOutputStream, keyEntries: List<BackupEntry>) {
        val bytes = BackupManager.encodeKeysArchive(keyEntries)
        zip.putNextEntry(ZipEntry(KEYS_ZIP))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeSource(zip: ZipOutputStream, sourceRoot: File) {
        val root = sourceRoot.canonicalFile
        root.walkTopDown()
            .onEnter { dir -> !java.nio.file.Files.isSymbolicLink(dir.toPath()) }
            .filter { file -> !java.nio.file.Files.isSymbolicLink(file.toPath()) }
            .forEach { file ->
                if (file == root) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry(SOURCE_PREFIX + relative).apply { time = file.lastModified() })
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(SOURCE_PREFIX + relative + "/"))
                    zip.closeEntry()
                }
            }
    }

    private fun countSourceFiles(sourceRoot: File): Int {
        val root = sourceRoot.canonicalFile
        return root.walkTopDown().count { it.isFile }
    }

    private fun writeSourceEntry(input: java.io.InputStream, targetDir: File, relative: String) {
        requireSafeMemberName(relative)
        if (relative.isBlank() || relative.endsWith("/")) return
        val target = File(targetDir, relative).canonicalFile
        val root = targetDir.canonicalFile
        if (!target.toPath().startsWith(root.toPath())) throw BackupException("Backup sadrži sumnjivu putanju: $relative")
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_SOURCE_ENTRY_BYTES) throw BackupException("Stavka backup-a prelazi dozvoljenu veličinu.")
                out.write(buffer, 0, read)
            }
        }
    }

    private fun requireSafeMemberName(name: String) {
        if (name.isBlank()) throw BackupException("Backup sadrži prazno ime stavke.")
        if (name.startsWith("/") || name.contains("\\") || name.split('/').any { it == ".." }) {
            throw BackupException("Backup sadrži sumnjivu putanju: $name")
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw BackupException("Stavka backup-a prelazi dozvoljenu veličinu.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun requireMinPassphrase(passphrase: CharArray) {
        if (passphrase.size < BackupManager.MIN_PASSPHRASE_LENGTH) {
            throw BackupException("Backup lozinka mora imati najmanje ${BackupManager.MIN_PASSPHRASE_LENGTH} karaktera.")
        }
    }

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(value: String): String = runCatching {
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrDefault("")

    companion object {
        const val PREFIX = "projects/"
        private const val SUFFIX = ".mhproj"
        private const val MANIFEST = "manifest.properties"
        private const val KEYS_ZIP = "keys.zip"
        private const val SOURCE_PREFIX = "source/"
        private const val FORMAT = "mobileharness-project-backup"
        private const val VERSION = 1
        private const val MAX_ZIP_ENTRIES = 200_000
        private const val MAX_KEYS_ZIP_BYTES = 64 * 1024 * 1024
        private const val MAX_SOURCE_ENTRY_BYTES = 512 * 1024 * 1024
    }
}
