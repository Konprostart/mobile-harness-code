package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebTemplateStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipWith(entries: Map<String, String>): File {
        val zip = tmp.newFile("template.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun extractZipCopiesNestedStructure() {
        val zip = zipWith(
            mapOf(
                "index.html" to "<html></html>",
                "css/style.css" to "body{}",
                "js/app.js" to "console.log(1)",
            ),
        )
        val dest = tmp.newFolder("out")
        WebTemplateStore.extractZip(zip, dest)
        assertTrue(File(dest, "index.html").isFile)
        assertTrue(File(dest, "css/style.css").isFile)
        assertTrue(File(dest, "js/app.js").isFile)
        assertEquals("body{}", File(dest, "css/style.css").readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun extractZipBlocksTraversal() {
        val zip = zipWith(mapOf("../evil.txt" to "bad"))
        WebTemplateStore.extractZip(zip, tmp.newFolder("out"))
    }

    @Test
    fun extractZipSkipsMacosxAndDsStore() {
        val zip = zipWith(
            mapOf(
                "index.html" to "ok",
                "__MACOSX/._index.html" to "junk",
                ".DS_Store" to "junk",
            ),
        )
        val dest = tmp.newFolder("out")
        WebTemplateStore.extractZip(zip, dest)
        assertTrue(File(dest, "index.html").isFile)
        assertFalse(File(dest, "__MACOSX").exists())
        assertFalse(File(dest, ".DS_Store").exists())
    }

    @Test
    fun downloadHostAllowlist() {
        assertTrue(WebTemplateStore.isAllowedDownloadHost("https://github.com/x/y.zip"))
        assertTrue(WebTemplateStore.isAllowedDownloadHost("https://codeload.github.com/x/y/tar.gz/refs/tags/v1"))
        assertTrue(WebTemplateStore.isAllowedDownloadHost("https://objects.githubusercontent.com/x"))
        assertTrue(WebTemplateStore.isAllowedDownloadHost("https://raw.githubusercontent.com/x/y/LICENSE"))
        assertFalse(WebTemplateStore.isAllowedDownloadHost("https://evil.com/x.zip"))
        assertFalse(WebTemplateStore.isAllowedDownloadHost("http://github.com/x.zip"))
        assertFalse(WebTemplateStore.isAllowedDownloadHost("file:///etc/passwd"))
    }

    @Test
    fun sha256HexIsDeterministic() {
        val file = tmp.newFile("f.txt")
        file.writeText("hello world")
        val expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        assertEquals(expected, sha256Hex(file))
    }

    @Test
    fun emptyCatalogIsValidAndHasNoReadyTemplates() {
        val catalog = WebTemplateCatalog.fromJson("[]")
        assertTrue(catalog.isEmpty())
        assertEquals(0, WebTemplateCatalog.readyCount(catalog))
    }

    private fun remoteTemplate(id: String = "dashboard-lex", sha256: String = "a".repeat(64)) = WebTemplate(
        id = id,
        name = "Law Firm",
        category = TemplateCategory.DASHBOARD,
        description = "d",
        license = "MIT",
        licenseUrl = "https://raw.githubusercontent.com/o/r/main/LICENSE",
        sourceUrl = "https://github.com/o/r",
        downloadUrl = "https://raw.githubusercontent.com/o/r/main/templates/lex.zip",
        sha256 = sha256,
    )

    @Test
    fun cachedDirForIsContentAddressedAndStable() {
        val root = tmp.newFolder("cache")
        val t = remoteTemplate()
        val dir1 = WebTemplateStore.cachedDirFor(root, t)
        val dir2 = WebTemplateStore.cachedDirFor(root, t)
        assertEquals(dir1, dir2)
        assertTrue(dir1.name.startsWith("template-dashboard-lex-"))
        // A different digest produces a different cache directory.
        val other = remoteTemplate(sha256 = "b".repeat(64))
        assertFalse(WebTemplateStore.cachedDirFor(root, other).name == dir1.name)
    }

    @Test
    fun isCompleteDirRequiresMarker() {
        val root = tmp.newFolder("cache")
        val dir = WebTemplateStore.cachedDirFor(root, remoteTemplate())
        dir.mkdirs()
        File(dir, "index.html").writeText("<html></html>")
        assertFalse(WebTemplateStore.isCompleteDir(dir))
        File(dir, WebTemplateStore.CACHE_COMPLETE_MARKER).writeText("ok")
        assertTrue(WebTemplateStore.isCompleteDir(dir))
    }
}
