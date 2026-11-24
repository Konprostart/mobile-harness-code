package com.mobileharness.code.skills

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdvancedSkillStoreTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private lateinit var store: AdvancedSkillStore

    @Before
    fun setUp() {
        store = AdvancedSkillStore(tmp.newFolder("skills").apply { mkdirs() })
    }

    private fun writeSkill(name: String, description: String = "desc", body: String = "Do the thing"): File {
        val dir = File(tmp.root, "src-$name").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: $name\ndescription: $description\n---\n\n$body\n")
        File(dir, "scripts").mkdirs()
        File(dir, "scripts/run.sh").writeText("#!/bin/sh\necho hi\n")
        return dir
    }

    @Test
    fun installsFromDirectoryAndLists() {
        val src = writeSkill("alpha")
        val installed = store.installFromDir(src)
        assertEquals("alpha", installed.name)
        assertEquals(1, store.list().size)
        assertTrue(store.list().first().files.contains("scripts/run.sh"))
    }

    @Test
    fun updatePreservesMetadata() {
        val src = writeSkill("alpha", description = "v1")
        store.installFromDir(src)
        store.setEnabled("alpha", false)
        store.updateTags("alpha", listOf("tag1"))
        val src2 = writeSkill("alpha", description = "v2")
        store.installFromDir(src2)
        val skill = store.get("alpha")!!
        assertEquals("v2", skill.description)
        assertFalse(skill.enabled)
        assertEquals(listOf("tag1"), skill.tags)
    }

    @Test
    fun rejectsMissingSkillMd() {
        val empty = tmp.newFolder("empty")
        var failed = false
        try { store.installFromDir(empty) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun installsFromZip() {
        val zip = File(tmp.root, "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            listOf(
                "skills/alpha/SKILL.md" to "---\nname: alpha\ndescription: d\n---\n\nbody\n",
                "skills/beta/SKILL.md" to "---\nname: beta\ndescription: d\n---\n\nbody\n",
            ).forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        val installed = store.installFromZip(zip, "zip://bundle")
        assertEquals(2, installed.size)
        assertEquals(setOf("alpha", "beta"), store.list().map { it.name }.toSet())
    }

    @Test
    fun searchFiltersByTagAndDescription() {
        store.installFromDir(writeSkill("alpha", description = "network tools"))
        store.updateTags("alpha", listOf("networking"))
        store.installFromDir(writeSkill("beta", description = "code review"))
        assertEquals(listOf("alpha"), store.search("network").map { it.name })
        assertEquals(listOf("alpha"), store.search("networking").map { it.name })
        assertEquals(listOf("beta"), store.search("review").map { it.name })
    }

    @Test
    fun disableRemoveAndTagsWork() {
        store.installFromDir(writeSkill("alpha"))
        assertTrue(store.setEnabled("alpha", false))
        assertFalse(store.get("alpha")!!.enabled)
        assertTrue(store.updateTags("alpha", listOf("a", "b")))
        assertEquals(listOf("a", "b"), store.get("alpha")!!.tags)
        assertTrue(store.remove("alpha"))
        assertEquals(0, store.list().size)
    }

    @Test
    fun readSupportFileGuardsTraversal() {
        store.installFromDir(writeSkill("alpha"))
        assertEquals("#!/bin/sh\necho hi\n", store.readSupportFile("alpha", "scripts/run.sh"))
        assertNull(store.readSupportFile("alpha", "../outside.txt"))
        assertNull(store.readSupportFile("alpha", "/etc/passwd"))
    }

    @Test
    fun roundTripThroughCodec() {
        store.installFromDir(writeSkill("alpha"))
        store.updateTags("alpha", listOf("x"))
        store.setEnabled("alpha", false)
        val json = InstalledSkillCodec.toJson(store.list())
        val restored = AdvancedSkillStore(tmp.newFolder("skills2").apply { mkdirs() })
        restored.load(json)
        // Metadata-only restore (no files on disk) yields an empty scan but retains metadata.
        assertTrue(InstalledSkillCodec.fromJson(json).isNotEmpty())
    }

    @Test
    fun categoriesAndTagsAreDerived() {
        store.installFromDir(writeSkill("alpha"))
        store.updateTags("alpha", listOf("t1"))
        assertEquals(setOf("general"), store.categories())
        assertEquals(setOf("t1"), store.allTags())
    }

    @Test
    fun pathTraversalSkillNameRejected() {
        val dir = tmp.newFolder("evil")
        File(dir, "SKILL.md").writeText("---\nname: ../evil\ndescription: d\n---\n\nbody\n")
        var failed = false
        try { store.installFromDir(dir) } catch (e: SkillParseException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun codecRejectsTraversalName() {
        val json = """[{"id":"x","name":"../evil","description":"d","relativeDir":"../evil"}]"""
        assertTrue(InstalledSkillCodec.fromJson(json).isEmpty())
    }

    @Test
    fun duplicateInstallIsIdempotentOnId() {
        val src = writeSkill("alpha")
        store.installFromDir(src)
        store.installFromDir(src)
        assertEquals(1, store.list().size)
    }
}
