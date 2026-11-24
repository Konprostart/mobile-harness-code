package com.mobileharness.code.plugins

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginArchiveTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, String>): File {
        val file = File(tmp.root, "bundle.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun extractsZipWithDirectories() {
        val archive = zip(
            mapOf(
                "plugin.json" to "{}",
                "scripts/run.sh" to "#!/bin/sh\necho hi\n",
                "assets/data.txt" to "hello",
            ),
        )
        val dest = tmp.newFolder("out")
        PluginArchive.extract(archive, dest)
        assertTrue(File(dest, "plugin.json").isFile)
        assertTrue(File(dest, "scripts/run.sh").isFile)
        assertEquals("hello", File(dest, "assets/data.txt").readText())
    }

    @Test
    fun blocksParentTraversal() {
        val archive = zip(mapOf("../evil.sh" to "bad", "ok.txt" to "fine"))
        val dest = tmp.newFolder("out2")
        PluginArchive.extract(archive, dest)
        assertFalse(File(dest, "../evil.sh").exists())
        assertFalse(File(tmp.root, "evil.sh").exists())
        assertTrue(File(dest, "ok.txt").isFile)
    }

    @Test
    fun blocksAbsolutePaths() {
        val archive = zip(mapOf("/tmp/evil.sh" to "bad", "ok.txt" to "fine"))
        val dest = tmp.newFolder("out3")
        PluginArchive.extract(archive, dest)
        assertFalse(File("/tmp/evil.sh").exists())
        assertTrue(File(dest, "ok.txt").isFile)
    }

    @Test
    fun skipsNodeModules() {
        val archive = zip(mapOf("node_modules/pkg/index.js" to "x", "plugin.json" to "{}"))
        val dest = tmp.newFolder("out4")
        PluginArchive.extract(archive, dest)
        assertFalse(File(dest, "node_modules").exists())
        assertTrue(File(dest, "plugin.json").isFile)
    }

    @Test
    fun rejectsNonArchive() {
        val notArchive = File(tmp.root, "file.txt").apply { writeText("plain") }
        val dest = tmp.newFolder("out5")
        var failed = false
        try { PluginArchive.extract(notArchive, dest) } catch (e: Exception) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun copyFolderCopiesRegularFiles() {
        val src = tmp.newFolder("src")
        File(src, "plugin.json").writeText("{}")
        File(src, "sub").mkdirs()
        File(src, "sub/a.txt").writeText("a")
        val dest = tmp.newFolder("dest")
        PluginArchive.copyFolder(src, dest)
        assertTrue(File(dest, "plugin.json").isFile)
        assertEquals("a", File(dest, "sub/a.txt").readText())
    }
}
