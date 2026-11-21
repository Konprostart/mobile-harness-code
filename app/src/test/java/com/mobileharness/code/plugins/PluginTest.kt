package com.mobileharness.code.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTest {

    private fun manifest(id: String = "com.example.demo") = PluginManifest(
        id = id,
        name = "Demo",
        version = "1.0.0",
        entry = "bin/run.sh",
        permissions = listOf(PluginPermission.FILESYSTEM_WRITE, PluginPermission.NETWORK),
    )

    @Test
    fun `valid manifest passes and path traversal entry is rejected`() {
        assertTrue(PluginPolicy.isValidManifest(manifest()))
        assertFalse(PluginPolicy.isValidManifest(manifest().copy(entry = "../evil.sh")))
    }

    @Test
    fun `manifest id allows dots and dashes but not slashes`() {
        assertTrue(PluginPolicy.isValidManifest(manifest("com.example.demo-plugin")))
        assertFalse(PluginPolicy.isValidManifest(manifest("com/example")))
        assertFalse(PluginPolicy.isValidManifest(manifest("")))
    }

    @Test
    fun `manifest with bad sha256 is rejected`() {
        assertFalse(PluginPolicy.isValidManifest(manifest().copy(sha256 = "not-a-hash")))
        assertTrue(PluginPolicy.isValidManifest(manifest().copy(sha256 = "a".repeat(64))))
    }

    @Test
    fun `bundle size cap is enforced`() {
        assertTrue(PluginPolicy.isValidBundleSize(64L * 1024 * 1024))
        assertFalse(PluginPolicy.isValidBundleSize(64L * 1024 * 1024 + 1))
    }

    @Test
    fun `manifest json round trips`() {
        val json = PluginCodec.encodeManifest(manifest())
        val decoded = PluginCodec.decodeManifest(json)
        assertEquals(manifest(), decoded)
    }

    @Test
    fun `manifest decode ignores malformed json and unknown permission`() {
        assertNull(PluginCodec.decodeManifest("not json"))
        val json = """{"id":"x","name":"X","version":"1","permissions":["NETWORK","BOGUS"]}"""
        assertEquals(listOf(PluginPermission.NETWORK), PluginCodec.decodeManifest(json)!!.permissions)
    }

    @Test
    fun `source resolver handles github shorthand and npm`() {
        assertEquals(PluginSource.GITHUB, PluginSourceResolver.resolve("acme/tool")!!.source)
        assertEquals("https://github.com/acme/tool", PluginSourceResolver.resolve("acme/tool")!!.ref)
        assertEquals(PluginSource.NPM, PluginSourceResolver.resolve("npm:@scope/pkg@1.2.3")!!.source)
        assertTrue(PluginSourceResolver.resolve("npm:@scope/pkg@1.2.3")!!.isNpmPackage)
    }

    @Test
    fun `source resolver rejects non-https and loopback hosts`() {
        assertNull(PluginSourceResolver.resolve("zip:http://evil.com/x.zip"))
        assertNull(PluginSourceResolver.resolve("zip:https://127.0.0.1/x.zip"))
        assertNull(PluginSourceResolver.resolve("zip:https://untrusted.example.com/x.zip"))
        assertNull(PluginSourceResolver.resolve(""))
    }

    @Test
    fun `registry install enforces policy and cap`() {
        val registry = PluginRegistry(maxPlugins = 2)
        assertTrue(registry.install(Plugin(manifest(), PluginSource.GITHUB, "https://github.com/a/b", "demo")))
        assertFalse(registry.install(Plugin(manifest().copy(id = "bad/id"), PluginSource.GITHUB, "x", "bad")))
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun `registry round trip preserves plugins`() {
        val registry = PluginRegistry()
        registry.install(Plugin(manifest(), PluginSource.ZIP, "https://github.com/a/b/releases/x.zip", "demo"))
        val json = PluginCodec.encodeRegistry(registry.snapshot())
        val restored = PluginRegistry()
        restored.load(json)
        assertEquals(1, restored.snapshot().size)
        assertEquals("com.example.demo", restored.snapshot().first().manifest.id)
    }

    @Test
    fun `registry remove and disable work`() {
        val registry = PluginRegistry()
        registry.install(Plugin(manifest(), PluginSource.GITHUB, "ref", "demo"))
        assertTrue(registry.setEnabled("com.example.demo", false))
        assertFalse(registry.get("com.example.demo")!!.enabled)
        assertTrue(registry.remove("com.example.demo"))
        assertEquals(0, registry.snapshot().size)
    }
}
