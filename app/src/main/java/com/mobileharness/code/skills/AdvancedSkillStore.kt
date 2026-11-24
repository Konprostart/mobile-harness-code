package com.mobileharness.code.skills

import com.mobileharness.code.plugins.PluginArchive
import java.io.File
import java.nio.file.Files

/**
 * Filesystem-backed store for SKILL.md skills, ported and adapted from AndroidHarness 4.0
 * (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * The skill *content* (SKILL.md + supporting files) lives on disk under [skillsDir]; the *metadata*
 * (enabled, tags, origin, install time) lives in a JSON index persisted through [persist]. The disk
 * is the source of truth for the skill body, so a skill edited on disk is always shown current,
 * while enable/disable/tag state survives across restarts.
 */
class AdvancedSkillStore(
    private val skillsDir: File,
    private val persist: (List<InstalledSkill>) -> Unit = {},
    private val maxSkills: Int = 64,
) {
    private val metadata = LinkedHashMap<String, InstalledSkill>()

    fun load(json: String?) {
        metadata.clear()
        InstalledSkillCodec.fromJson(json ?: "").forEach { metadata[it.id] = it }
    }

    /** The full installed-skill list, content read fresh from disk and merged with saved metadata. */
    fun list(): List<InstalledSkill> = scanDisk().sortedWith(compareBy({ it.category }, { it.name }))

    fun get(id: String): InstalledSkill? = scanDisk().firstOrNull { it.id == id }

    fun categories(): Set<String> = list().map { it.category }.toSortedSet()

    fun allTags(): Set<String> = list().flatMap { it.tags }.toSortedSet()

    fun search(query: String): List<InstalledSkill> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        return list().filter { skill ->
            skill.name.lowercase().contains(q) ||
                skill.description.lowercase().contains(q) ||
                skill.category.lowercase().contains(q) ||
                skill.tags.any { it.lowercase().contains(q) }
        }
    }

    /**
     * Installs (or updates) a skill from a local directory that contains a SKILL.md. On update the
     * skill content is replaced but the user metadata (enabled, tags, install time, origin) is kept.
     */
    fun installFromDir(source: File, sourceRef: String = "local"): InstalledSkill {
        val skillMd = File(source, "SKILL.md")
        require(skillMd.isFile) { "No SKILL.md found in ${source.name}." }
        val raw = skillMd.readText()
        val parsed = runCatching { SkillParser.validateUserContent(raw) }
            .getOrElse { throw SkillParseException(it.message ?: "Invalid skill.") }
        return installParsed(source, parsed, sourceRef, parsed.name)
    }

    /** Extracts a ZIP bundle and installs every SKILL.md skill found inside it. */
    fun installFromZip(zip: File, sourceRef: String): List<InstalledSkill> {
        val tmp = File(skillsDir, ".import-${System.currentTimeMillis()}")
        try {
            PluginArchive.extract(zip, tmp)
            val folders = findSkillFolders(tmp)
            if (folders.isEmpty()) throw SkillParseException("No SKILL.md skills were found in the archive.")
            return folders.map { folder ->
                val skillMd = File(folder, "SKILL.md")
                val parsed = runCatching { SkillParser.validateUserContent(skillMd.readText()) }
                    .getOrElse { throw SkillParseException(it.message ?: "Invalid skill in ${folder.name}.") }
                installParsed(folder, parsed, sourceRef, parsed.name)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = get(id) ?: return false
        val updated = current.copy(enabled = enabled, updatedAtMillis = System.currentTimeMillis())
        metadata[id] = updated
        persist()
        return true
    }

    fun remove(id: String): Boolean {
        val current = get(id) ?: metadata[id] ?: return false
        metadata.remove(id)
        val dir = File(skillsDir, current.relativeDir)
        if (dir.isDirectory && dir.canonicalFile.toPath().startsWith(skillsDir.canonicalFile.toPath())) {
            dir.deleteRecursively()
        }
        persist()
        return true
    }

    fun updateTags(id: String, tags: List<String>): Boolean {
        val current = get(id) ?: return false
        val clean = tags.map { it.trim().take(InstalledSkill.MAX_TAG_CHARS) }
            .filter { it.isNotBlank() }.distinct().take(InstalledSkill.MAX_TAGS)
        val updated = current.copy(tags = clean, updatedAtMillis = System.currentTimeMillis())
        metadata[id] = updated
        persist()
        return true
    }

    fun updateMetadata(id: String, description: String? = null, category: String? = null): Boolean {
        val current = get(id) ?: return false
        val updated = current.copy(
            description = description?.take(2000) ?: current.description,
            category = category?.ifBlank { "general" }?.take(120) ?: current.category,
            updatedAtMillis = System.currentTimeMillis(),
        )
        metadata[id] = updated
        persist()
        return true
    }

    /** Safely reads a support file inside a skill directory; null when missing or escaping the skill. */
    fun readSupportFile(id: String, relativePath: String): String? {
        val skill = get(id) ?: return null
        val cleaned = relativePath.trim().replace('\\', '/').trimStart('/')
        if (cleaned.isBlank() || cleaned.contains("..")) return null
        val dir = File(skillsDir, skill.relativeDir)
        val file = File(dir, cleaned)
        if (!Files.isSymbolicLink(file.toPath()) &&
            file.isFile &&
            file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        ) {
            if (file.length() > MAX_SUPPORT_READ) return "File is ${file.length() / 1024} KiB, too large to show."
            return runCatching { file.readText() }.getOrNull()
        }
        return null
    }

    private fun installParsed(source: File, parsed: ParsedSkill, sourceRef: String, id: String): InstalledSkill {
        if (metadata.size >= maxSkills && metadata[id] == null) {
            throw SkillParseException("Too many skills installed (max $maxSkills).")
        }
        val existing = metadata[id]
        val dest = File(skillsDir, parsed.name)
        if (dest.exists()) dest.deleteRecursively()
        PluginArchive.copyFolder(source, dest)
        val now = System.currentTimeMillis()
        val files = listSupportFiles(dest)
        val installed = InstalledSkill(
            id = id,
            name = parsed.name,
            description = parsed.description,
            category = parsed.category,
            body = parsed.body,
            raw = parsed.raw,
            fields = parsed.fields,
            source = existing?.source ?: SkillSource.USER,
            sourceRef = existing?.sourceRef ?: sourceRef,
            relativeDir = dest.name,
            files = files,
            tags = existing?.tags ?: emptyList(),
            version = parsed.fields["version"] ?: "0.0.0",
            enabled = existing?.enabled ?: true,
            installedAtMillis = existing?.installedAtMillis ?: now,
            updatedAtMillis = now,
        )
        metadata[id] = installed
        persist()
        return installed
    }

    /** Recursively finds directories that hold a SKILL.md, in stable order. */
    private fun findSkillFolders(root: File): List<File> {
        if (File(root, "SKILL.md").isFile) return listOf(root)
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in PluginArchive.SKIPPED }
            .flatMap { findSkillFolders(it) }
            .sortedBy { it.name }
            .take(MAX_SKILL_FOLDERS)
    }

    private fun listSupportFiles(dir: File): List<String> {
        val out = mutableListOf<String>()
        for (folder in InstalledSkill.SUPPORT_DIRS) {
            val sub = File(dir, folder)
            if (!sub.isDirectory) continue
            sub.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = "$folder/${file.relativeTo(sub).invariantSeparatorsPath}"
                if (rel.length <= 240) out += rel
                if (out.size >= InstalledSkill.MAX_FILES) return out
            }
        }
        return out.sorted()
    }

    /** Reads the on-disk skills and merges saved metadata (enabled/tags/origin). */
    private fun scanDisk(): List<InstalledSkill> {
        if (!skillsDir.isDirectory) return emptyList()
        return skillsDir.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "SKILL.md").isFile }
            .mapNotNull { dir ->
                val raw = runCatching { File(dir, "SKILL.md").readText() }.getOrNull() ?: return@mapNotNull null
                val parsed = runCatching { SkillParser.parse(raw) }.getOrNull() ?: return@mapNotNull null
                val existing = metadata[parsed.name] ?: metadata.values.firstOrNull { it.relativeDir == dir.name }
                InstalledSkill(
                    id = existing?.id ?: parsed.name,
                    name = parsed.name,
                    description = parsed.description,
                    category = parsed.category,
                    body = parsed.body,
                    raw = parsed.raw,
                    fields = parsed.fields,
                    source = existing?.source ?: SkillSource.USER,
                    sourceRef = existing?.sourceRef ?: "local",
                    relativeDir = dir.name,
                    files = listSupportFiles(dir),
                    tags = existing?.tags ?: emptyList(),
                    version = parsed.fields["version"] ?: "0.0.0",
                    enabled = existing?.enabled ?: true,
                    installedAtMillis = existing?.installedAtMillis ?: dir.lastModified(),
                    updatedAtMillis = existing?.updatedAtMillis ?: dir.lastModified(),
                )
            }
    }

    private fun persist() = persist(metadata.values.toList())

    private companion object {
        const val MAX_SUPPORT_READ = 256L * 1024
        const val MAX_SKILL_FOLDERS = 256
    }
}
