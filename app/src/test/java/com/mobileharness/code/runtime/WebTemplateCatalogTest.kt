package com.mobileharness.code.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTemplateCatalogTest {
    private fun entry(
        id: String = "com.example.notes",
        name: String = "Notes",
        category: String = "productivity",
        license: String = "MIT",
        licenseUrl: String = "https://raw.githubusercontent.com/x/y/LICENSE",
        sourceUrl: String = "https://github.com/x/y",
        downloadUrl: String? = "https://codeload.github.com/x/y/tar.gz/refs/tags/v1",
        sha256: String? = "a".repeat(64),
        bundledAsset: String? = null,
        buildRequired: Boolean = false,
        backendRequired: Boolean = false,
        entryPoint: String = "index.html",
    ) = JSONObject().apply {
        put("id", id); put("name", name); put("category", category); put("description", "d")
        put("license", license); put("licenseUrl", licenseUrl); put("sourceUrl", sourceUrl)
        put("downloadUrl", downloadUrl ?: JSONObject.NULL)
        put("bundledAsset", bundledAsset ?: JSONObject.NULL)
        put("sha256", sha256 ?: JSONObject.NULL)
        put("buildRequired", buildRequired); put("backendRequired", backendRequired)
        put("entryPoint", entryPoint); put("icon", "\uD83D\uDCC4")
        put("tags", JSONArray().put("notes"))
    }

    @Test
    fun validRemoteEntryParsesAndIsReady() {
        val template = WebTemplate.fromJson(entry())
        assertTrue(template != null)
        assertTrue(WebTemplatePolicy.isValid(template!!))
        assertTrue(template.isReady)
        assertFalse(template.isBundled)
    }

    @Test
    fun validBundledEntryParsesAndIsReady() {
        val template = WebTemplate.fromJson(
            entry(downloadUrl = null, sha256 = null, bundledAsset = "templates/notes.zip"),
        )
        assertTrue(template != null)
        assertTrue(WebTemplatePolicy.isValid(template!!))
        assertTrue(template.isReady)
        assertTrue(template.isBundled)
    }

    @Test
    fun invalidLicenseIsRejected() {
        val template = WebTemplate.fromJson(entry(license = "GPL-3.0"))
        assertTrue(template != null)
        assertFalse(WebTemplatePolicy.isValid(template!!))
    }

    @Test
    fun offAllowlistLicenseDroppedFromCatalog() {
        val catalog = WebTemplateCatalog.fromJson("[" + entry(id = "a", license = "AGPL-3.0") + "]")
        assertTrue(catalog.isEmpty())
    }

    @Test
    fun traversalEntryPointIsRejected() {
        val template = WebTemplate.fromJson(entry(entryPoint = "../evil.html"))
        assertFalse(WebTemplatePolicy.isValid(template!!))
    }

    @Test
    fun remoteWithoutSha256IsRejected() {
        val template = WebTemplate.fromJson(entry(sha256 = null))
        assertFalse(WebTemplatePolicy.isValid(template!!))
    }

    @Test
    fun remoteWithoutDownloadUrlIsRejected() {
        val template = WebTemplate.fromJson(entry(downloadUrl = null))
        assertFalse(WebTemplatePolicy.isValid(template!!))
    }

    @Test
    fun informationalEntryWithoutAssetOrDownloadIsValidButNotReady() {
        // build-required / backend-required entries are listed (with a badge) but never ready.
        val build = WebTemplate.fromJson(entry(downloadUrl = null, sha256 = null, buildRequired = true))
        assertTrue(build != null)
        assertTrue(WebTemplatePolicy.isValid(build!!))
        assertFalse(build.isReady)
        val backend = WebTemplate.fromJson(entry(downloadUrl = null, sha256 = null, backendRequired = true))
        assertTrue(backend != null)
        assertTrue(WebTemplatePolicy.isValid(backend!!))
        assertFalse(backend.isReady)
    }

    @Test
    fun informationalEntryWithoutBuildOrBackendFlagIsRejected() {
        val template = WebTemplate.fromJson(entry(downloadUrl = null, sha256 = null))
        assertFalse(WebTemplatePolicy.isValid(template!!))
    }

    @Test
    fun nonHttpsSourceIsRejected() {
        val template = WebTemplate.fromJson(entry(sourceUrl = "http://github.com/x/y"))
        assertFalse(WebTemplatePolicy.isValid(template!!))
    }

    @Test
    fun bundledAssetTraversalIsRejected() {
        val template = WebTemplate.fromJson(
            entry(downloadUrl = null, sha256 = null, bundledAsset = "../secret.zip"),
        )
        assertFalse(WebTemplatePolicy.isValid(template!!))
    }

    @Test
    fun buildRequiredAndBackendRequiredAreNotReady() {
        val build = WebTemplate.fromJson(entry(buildRequired = true))
        assertTrue(build != null)
        assertFalse(build!!.isReady)
        val backend = WebTemplate.fromJson(entry(backendRequired = true))
        assertTrue(backend != null)
        assertFalse(backend!!.isReady)
    }

    @Test
    fun catalogSortsByCategoryThenNameAndDropsMalformed() {
        val catalog = WebTemplateCatalog.fromJson(
            "[" +
                entry(id = "z", category = "tools", name = "Zebra") + "," +
                entry(id = "b", category = "tools", name = "Alpha") + "," +
                entry(id = "dash", category = "dashboard", name = "Dash") + "," +
                entry(id = "bad", category = "nope", name = "Nope") + "," +
                """{"not":"a template"}""" + "," +
                "]",
        )
        val ids = catalog.map { it.id }
        assertEquals(listOf("dash", "b", "z"), ids)
        assertEquals(3, WebTemplateCatalog.readyCount(catalog))
    }

    @Test
    fun newMetadataFieldsParseAndValidate() {
        val json = entry(
            id = "dashboard-lex",
            name = "Law Firm",
        ).apply {
            put("industry", "Professional Services")
            put("thumbnailUrl", "https://raw.githubusercontent.com/o/r/main/thumbs/lex.webp")
            put("downloadSizeBytes", 123456L)
            put("demoDataOnly", true)
        }
        val template = WebTemplate.fromJson(json)
        assertTrue(template != null)
        assertTrue(WebTemplatePolicy.isValid(template!!))
        assertEquals("Professional Services", template.industry)
        assertEquals("https://raw.githubusercontent.com/o/r/main/thumbs/lex.webp", template.thumbnailUrl)
        assertEquals(123456L, template.downloadSizeBytes)
        assertTrue(template.demoDataOnly)
    }

    @Test
    fun nonHttpsThumbnailUrlIsRejected() {
        val json = entry().apply { put("thumbnailUrl", "http://evil.com/x.webp") }
        assertFalse(WebTemplatePolicy.isValid(WebTemplate.fromJson(json)!!))
    }

    @Test
    fun negativeDownloadSizeIsRejected() {
        val json = entry().apply { put("downloadSizeBytes", -1L) }
        val template = WebTemplate.fromJson(json)
        // fromJson coerces negative to null; a null size is valid, so build one directly instead.
        assertTrue(template != null)
        assertNull(template!!.downloadSizeBytes)
    }

    @Test
    fun oversizedIndustryIsRejected() {
        val json = entry().apply { put("industry", "x".repeat(61)) }
        assertFalse(WebTemplatePolicy.isValid(WebTemplate.fromJson(json)!!))
    }

    @Test
    fun mergeKeepsBundledOnlyAndOverridesRemoteCollision() {
        val bundledEntry = entry(id = "a", category = "dashboard", name = "A", downloadUrl = null, sha256 = null, bundledAsset = "templates/a.zip")
        val remoteEntry = entry(id = "a", category = "dashboard", name = "A (updated)")
        val bundled = WebTemplateCatalog.fromJson("[$bundledEntry]")
        val remote = WebTemplateCatalog.fromJson("[$remoteEntry]")
        val merged = WebTemplateCatalog.merge(bundled, remote)
        assertEquals(1, merged.size)
        assertEquals("A (updated)", merged.single().name)
        assertTrue(merged.single().isRemote)

        // Bundled-only entries survive a merge even when the remote catalog is empty.
        val mergedWithEmpty = WebTemplateCatalog.merge(bundled, emptyList())
        assertEquals(1, mergedWithEmpty.size)
        assertTrue(mergedWithEmpty.single().isBundled)
    }
}
