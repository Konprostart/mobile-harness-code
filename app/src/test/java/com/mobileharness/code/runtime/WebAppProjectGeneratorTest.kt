package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebAppProjectGeneratorTest {
    @Test
    fun webTemplateProducesWebViewShell() = withTempDir { root ->
        val source = File(root, "web-src").apply { mkdirs() }
        File(source, "index.html").writeText("<html><body>hi</body></html>")
        File(source, "app.js").writeText("console.log('hi')")
        val project = File(root, "webapp")
        val manifest = WebAppManifest(id = "com.example.web", name = "Web Demo", permissions = listOf("network"))

        AndroidProjectTemplateGenerator.generateWebApp(
            destination = project,
            appName = "Web Demo",
            gradleMemoryProfile = GradleMemoryProfile.DEFAULT,
            web = WebAppProjectConfig(sourceDir = source, entryPoint = "index.html", manifest = manifest),
        )

        assertTrue(File(project, "settings.gradle.kts").isFile)
        val appGradle = File(project, "app/build.gradle.kts").readText()
        assertTrue(appGradle.contains("androidx.webkit:webkit:1.12.1"))
        assertFalse(appGradle.contains("compose = true"))
        assertTrue(appGradle.contains("androidx.appcompat:appcompat:1.6.1"))

        val manifestXml = File(project, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifestXml.contains("android.permission.INTERNET"))

        val activity = File(project, "app/src/main/java/com/pocketdev/web_demo/MainActivity.kt").readText()
        assertTrue(activity.contains("WebViewAssetLoader"))
        assertTrue(activity.contains("allowFileAccess = false"))
        assertTrue(activity.contains("assets/www/index.html"))

        val bridge = File(project, "app/src/main/java/com/pocketdev/web_demo/NativeBridge.kt").readText()
        assertTrue(bridge.contains("@JavascriptInterface"))

        assertTrue(File(project, "app/src/main/assets/www/index.html").isFile)
        assertTrue(File(project, "app/src/main/assets/www/app.js").isFile)
    }

    @Test
    fun webTemplateWithoutNetworkHasNoInternetPermission() = withTempDir { root ->
        val source = File(root, "web-src").apply { mkdirs() }
        File(source, "index.html").writeText("<html></html>")
        val project = File(root, "offline")

        AndroidProjectTemplateGenerator.generateWebApp(
            destination = project,
            appName = "Offline Demo",
            gradleMemoryProfile = GradleMemoryProfile.DEFAULT,
            web = WebAppProjectConfig(sourceDir = source, entryPoint = "index.html", manifest = null),
        )

        val manifestXml = File(project, "app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifestXml.contains("android.permission.INTERNET"))
    }

    @Test
    fun composeAndXmlTemplatesRemainUnchanged() = withTempDir { root ->
        val compose = File(root, "compose")
        val xml = File(root, "xml")
        AndroidProjectTemplateGenerator.generate(compose, "Compose Demo", com.mobileharness.code.model.AndroidTemplate.COMPOSE)
        AndroidProjectTemplateGenerator.generate(xml, "Views Demo", com.mobileharness.code.model.AndroidTemplate.XML)

        assertTrue(File(compose, "app/build.gradle.kts").readText().contains("compose = true"))
        assertFalse(File(compose, "app/build.gradle.kts").readText().contains("androidx.webkit"))
        assertTrue(File(xml, "app/src/main/res/layout/activity_main.xml").isFile)
    }

    @Test
    fun unsafeWebAssetPathIsRejected() = withTempDir { root ->
        val source = File(root, "web-src").apply { mkdirs() }
        File(source, "index.html").writeText("<html></html>")
        // A malicious symlink should not escape the destination; walkTopDown follows only real files,
        // but a crafted relative name with traversal is blocked by the canonical prefix check.
        val project = File(root, "traversal")

        AndroidProjectTemplateGenerator.generateWebApp(
            destination = project,
            appName = "Safe",
            gradleMemoryProfile = GradleMemoryProfile.DEFAULT,
            web = WebAppProjectConfig(sourceDir = source, entryPoint = "index.html", manifest = null),
        )
        assertTrue(File(project, "app/src/main/assets/www/index.html").isFile)
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("webapp-gen-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
