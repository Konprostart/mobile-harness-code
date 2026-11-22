package com.mobileharness.code.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppManifestTest {
    @Test
    fun validManifestIsAccepted() {
        val manifest = WebAppManifest(
            id = "com.example.notes",
            name = "Notes App",
            version = "1.2.3",
            entryPoint = "index.html",
            theme = "#FF5722",
            permissions = listOf("network"),
        )
        assertTrue(WebAppManifestPolicy.isValidManifest(manifest))
        assertTrue(WebAppManifestPolicy.normalize(manifest).permissions == listOf("network"))
    }

    @Test
    fun idRejectsTraversalAndSlashes() {
        assertFalse(WebAppManifestPolicy.isValidManifest(WebAppManifest(id = "../evil", name = "x")))
        assertFalse(WebAppManifestPolicy.isValidManifest(WebAppManifest(id = "a/b", name = "x")))
        assertFalse(WebAppManifestPolicy.isValidManifest(WebAppManifest(id = "", name = "x")))
    }

    @Test
    fun entryPointRejectsTraversal() {
        assertFalse(WebAppManifestPolicy.isValidEntryPoint("../../index.html"))
        assertFalse(WebAppManifestPolicy.isValidEntryPoint("/index.html"))
        assertFalse(WebAppManifestPolicy.isValidEntryPoint("a\\index.html"))
        assertFalse(WebAppManifestPolicy.isValidEntryPoint("index.txt"))
        assertTrue(WebAppManifestPolicy.isValidEntryPoint("index.html"))
        assertTrue(WebAppManifestPolicy.isValidEntryPoint("sub/index.html"))
    }

    @Test
    fun unknownPermissionsAreDropped() {
        val manifest = WebAppManifest(
            id = "com.example.app",
            name = "App",
            permissions = listOf("network", "camera", "exec", "read_files"),
        )
        val normalized = WebAppManifestPolicy.normalize(manifest)
        assertEquals(listOf("network", "camera"), normalized.permissions)
    }

    @Test
    fun themeMustBeHexColor() {
        assertTrue(WebAppManifestPolicy.isValidManifest(WebAppManifest(id = "a.b", name = "x", theme = "#FF5722")))
        assertFalse(WebAppManifestPolicy.isValidManifest(WebAppManifest(id = "a.b", name = "x", theme = "red")))
        assertFalse(WebAppManifestPolicy.isValidManifest(WebAppManifest(id = "a.b", name = "x", theme = "#FFF")))
    }

    @Test
    fun sha256MustBeHex64() {
        assertTrue(
            WebAppManifestPolicy.isValidManifest(
                WebAppManifest(id = "a.b", name = "x", sha256 = "a".repeat(64)),
            ),
        )
        assertFalse(WebAppManifestPolicy.isValidManifest(WebAppManifest(id = "a.b", name = "x", sha256 = "zzz")))
    }

    @Test
    fun parseReturnsNullForInvalidJson() {
        assertNull(WebAppManifestPolicy.parse(JSONObject("""{"id":"../x","name":"X"}""")))
        assertNull(WebAppManifestPolicy.parse(JSONObject("""{"id":"com.x","name":""}""")))
    }

    @Test
    fun parseNormalizesValidJson() {
        val parsed = WebAppManifestPolicy.parse(
            JSONObject("""{"id":"com.example.app","name":"My App","entryPoint":"app/index.html","permissions":["network","exec"]}"""),
        )
        assertNotNull(parsed)
        assertEquals("My App", parsed?.name)
        assertEquals("app/index.html", parsed?.entryPoint)
        assertEquals(listOf("network"), parsed?.permissions)
    }
}
