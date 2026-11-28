package com.mobileharness.code.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRuntimeDetectionTest {

    @Test
    fun mapsCommandsToPackages() {
        assertEquals(listOf("python"), PluginRuntimeDetection.forCommand("python3 run.py"))
        assertEquals(listOf("nodejs"), PluginRuntimeDetection.forCommand("node index.js"))
        assertEquals(listOf("nodejs", "npm"), PluginRuntimeDetection.forCommand("npx -y pkg"))
        assertEquals(listOf("php"), PluginRuntimeDetection.forCommand("php script.php"))
        assertEquals(listOf("ruby"), PluginRuntimeDetection.forCommand("ruby x.rb"))
        assertEquals(listOf("perl"), PluginRuntimeDetection.forCommand("perl x.pl"))
    }

    @Test
    fun mapsUtilityToolCommandsToPackages() {
        assertEquals(listOf("jq"), PluginRuntimeDetection.forCommand("jq -r .name data.json"))
        assertEquals(listOf("ripgrep"), PluginRuntimeDetection.forCommand("rg -n 'TODO' src"))
        assertEquals(listOf("fd-find"), PluginRuntimeDetection.forCommand("fd --type f main.kt"))
        assertEquals(listOf("fd-find"), PluginRuntimeDetection.forCommand("fdfind 'test' ."))
        assertEquals(listOf("zip"), PluginRuntimeDetection.forCommand("zip -r out.zip dir"))
        assertEquals(listOf("unzip"), PluginRuntimeDetection.forCommand("unzip archive.zip"))
    }

    @Test
    fun mapsScriptExtensions() {
        assertEquals(listOf("python"), PluginRuntimeDetection.forCommand("run script.py"))
        assertEquals(listOf("nodejs"), PluginRuntimeDetection.forCommand("run script.js"))
    }

    @Test
    fun mapsDenoToItsOwnRuntime() {
        assertEquals(listOf("deno"), PluginRuntimeDetection.forCommand("deno --version"))
        assertEquals(listOf("deno"), PluginRuntimeDetection.forCommand("deno task start"))
        assertEquals(listOf("deno"), PluginRuntimeDetection.forCommand("deno eval '1+1'"))
    }

    @Test
    fun doesNotMapBunToNode() {
        // Bun is deliberately not a supported runtime; it must not be reported as
        // needing Node.js/npm (its binary statically links LGPL code).
        assertEquals(emptyList<String>(), PluginRuntimeDetection.forCommand("bun install"))
        assertEquals(emptyList<String>(), PluginRuntimeDetection.forCommand("bunx create-app"))
    }

    @Test
    fun forManifestAggregatesEntryAndTools() {
        val manifest = PluginManifest(
            id = "x", name = "x", version = "1",
            entry = "node index.js",
            tools = listOf(PluginTool("t", command = "scripts/t.py")),
        )
        val runtimes = PluginRuntimeDetection.forManifest(manifest)
        assertTrue(runtimes.contains("nodejs"))
        assertTrue(runtimes.contains("python"))
    }

    @Test
    fun interpreterForKnownExtensions() {
        assertEquals("python3", PluginRuntimeDetection.interpreterFor("run.py"))
        assertEquals("node", PluginRuntimeDetection.interpreterFor("run.js"))
        assertEquals("ruby", PluginRuntimeDetection.interpreterFor("run.rb"))
        assertEquals("php", PluginRuntimeDetection.interpreterFor("run.php"))
        assertEquals("perl", PluginRuntimeDetection.interpreterFor("run.pl"))
        assertNull(PluginRuntimeDetection.interpreterFor("run.unknown"))
    }
}
