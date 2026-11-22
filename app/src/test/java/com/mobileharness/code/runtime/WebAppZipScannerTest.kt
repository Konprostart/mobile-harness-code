package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebAppZipScannerTest {
    @Test
    fun staticRootIndexIsDetected() = withTempDir { root ->
        File(root, "index.html").writeText("<html></html>")
        File(root, "style.css").writeText("body{}")

        val result = WebAppZipScanner.scan(root)

        assertEquals(WebAppKind.STATIC, result.kind)
        assertTrue(result.isReady)
        assertEquals("index.html", result.entryPoint)
        assertEquals(root.canonicalFile, result.assetRoot.canonicalFile)
        assertFalse(result.hasServiceWorker)
    }

    @Test
    fun nestedSingleDirectoryIndexIsDetected() = withTempDir { root ->
        val nested = File(root, "my-app").apply { mkdirs() }
        File(nested, "index.html").writeText("<html></html>")
        File(nested, "app.js").writeText("console.log(1)")

        val result = WebAppZipScanner.scan(root)

        assertEquals(WebAppKind.STATIC, result.kind)
        assertEquals("index.html", result.entryPoint)
        assertEquals(nested.canonicalFile, result.assetRoot.canonicalFile)
    }

    @Test
    fun builtSpaDistIndexIsDetected() = withTempDir { root ->
        val dist = File(root, "dist").apply { mkdirs() }
        File(dist, "index.html").writeText("<html></html>")
        File(dist, "assets/index.js").apply { parentFile.mkdirs(); writeText("x") }

        val result = WebAppZipScanner.scan(root)

        assertEquals(WebAppKind.BUILT_SPA, result.kind)
        assertTrue(result.isReady)
        assertEquals(dist.canonicalFile, result.assetRoot.canonicalFile)
        assertEquals("index.html", result.entryPoint)
    }

    @Test
    fun npmProjectWithoutBuildIsNotAutoBuilt() = withTempDir { root ->
        File(root, "package.json").writeText("""{"name":"app"}""")
        File(root, "src").mkdirs()
        File(root, "src/main.js").writeText("console.log(1)")

        val result = WebAppZipScanner.scan(root)

        assertEquals(WebAppKind.NPM_REQUIRED, result.kind)
        assertFalse(result.isReady)
    }

    @Test
    fun backendProjectIsReported() = withTempDir { root ->
        File(root, "requirements.txt").writeText("django")
        File(root, "manage.py").writeText("# backend")

        val result = WebAppZipScanner.scan(root)

        assertEquals(WebAppKind.BACKEND_REQUIRED, result.kind)
        assertFalse(result.isReady)
    }

    @Test
    fun emptyArchiveIsInvalid() = withTempDir { root ->
        val result = WebAppZipScanner.scan(root)
        assertEquals(WebAppKind.INVALID, result.kind)
        assertFalse(result.isReady)
    }

    @Test
    fun customManifestOverridesEntryPoint() = withTempDir { root ->
        File(root, "app/index.html").apply { parentFile.mkdirs(); writeText("<html></html>") }
        File(root, "manifest.json").writeText(
            """{"id":"com.example.app","name":"Custom","entryPoint":"app/index.html","permissions":["network"]}""",
        )

        val result = WebAppZipScanner.scan(root)

        assertEquals(WebAppKind.STATIC, result.kind)
        assertEquals("app/index.html", result.entryPoint)
        assertNotNull(result.manifest)
        assertEquals("Custom", result.manifest?.name)
    }

    @Test
    fun serviceWorkerIsDetected() = withTempDir { root ->
        File(root, "index.html").writeText("<html></html>")
        File(root, "service-worker.js").writeText("self.addEventListener('fetch', () => {})")

        val result = WebAppZipScanner.scan(root)

        assertTrue(result.hasServiceWorker)
        assertTrue(result.isReady)
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("webapp-scan-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
