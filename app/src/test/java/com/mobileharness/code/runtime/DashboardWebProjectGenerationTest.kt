package com.mobileharness.code.runtime

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end pipeline check for the 100-dashboard collection: each real dashboard ZIP is extracted
 * with the same guards as ZIP import, scanned for its entry point and turned into a WEB project by
 * [AndroidProjectTemplateGenerator]. The zips live outside the repo (hosted), so this test is a
 * no-op in CI unless `dashboard.zips.dir` points at a directory of `<slug>.zip` archives.
 *
 * Run locally with:
 *   ./gradlew :app:testOnlineDebugUnitTest --tests '*DashboardWebProjectGenerationTest*' \
 *     -Ddashboard.zips.dir=/path/to/templates
 */
class DashboardWebProjectGenerationTest {
    private val slugs = listOf("lex", "pulse", "scrub")

    private fun zipDir(): File =
        File(System.getProperty("dashboard.zips.dir") ?: "/tmp/templates-host/templates")

    @Test
    fun realDashboardZipsGenerateValidWebProjects() {
        val outputRoot = File(System.getProperty("dashboard.projects.out") ?: "/tmp/dashboard-projects")
        for (slug in slugs) {
            val zip = File(zipDir(), "$slug.zip")
            assumeTrue("skip: ${zip.absolutePath} not present", zip.isFile)

            val staging = File(outputRoot, ".stage-$slug").apply { mkdirs(); deleteRecursively(); mkdirs() }
            val project = File(outputRoot, slug)
            project.deleteRecursively()
            try {
                WebTemplateStore.extractZip(zip, staging)
                val scan = WebAppZipScanner.scan(staging)
                assertTrue("$slug not ready (${scan.kind})", scan.isReady)
                val appName = scan.manifest?.name?.takeIf { it.isNotBlank() } ?: slug
                AndroidProjectTemplateGenerator.generateWebApp(
                    destination = project,
                    appName = appName,
                    gradleMemoryProfile = GradleMemoryProfile.DEFAULT,
                    web = WebAppProjectConfig(sourceDir = scan.assetRoot, entryPoint = scan.entryPoint, manifest = scan.manifest),
                )
                val packagePath = AndroidProjectTemplateGenerator.packageName(appName).replace('.', '/')
                assertTrue("$slug: index.html missing", File(project, "app/src/main/assets/www/index.html").isFile)
                assertTrue("$slug: MainActivity missing", File(project, "app/src/main/java/$packagePath/MainActivity.kt").isFile)
                assertTrue("$slug: manifest missing", File(project, "app/src/main/AndroidManifest.xml").isFile)
            } finally {
                staging.deleteRecursively()
            }
        }
    }
}
