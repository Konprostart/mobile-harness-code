package com.mobileharness.code.plugins

import com.mobileharness.code.mcp.McpTransport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginFormatsTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun adaptsNativeManifest() {
        val root = tmp.newFolder("native")
        File(root, "plugin.json").writeText(
            """{"name":"demo-plugin","version":"1.0.0","description":"Demo",
               "skills":["skills"],"instructions":["INSTALL.md"],"entry":"bin/run.sh",
               "tools":[{"name":"hello","command":"scripts/hello.py"}]}""",
        )
        File(root, "skills").mkdirs()
        File(root, "skills/one").mkdirs()
        File(root, "skills/one/SKILL.md").writeText("---\nname: one\ndescription: d\n---\n\nbody\n")
        val adapted = PluginFormats.adapt(root, "fallback")
        assertEquals("demo-plugin", adapted.manifest.name)
        assertEquals("AndroidHarness", adapted.manifest.format)
        assertEquals(listOf("skills"), adapted.skillDirs)
        assertTrue(adapted.manifest.tools.any { it.name == "hello" })
        assertTrue(adapted.manifest.runtimes.contains("python"))
    }

    @Test
    fun adaptsClaudeCodePlugin() {
        val root = tmp.newFolder("claude")
        File(root, ".claude-plugin").mkdirs()
        File(root, ".claude-plugin/plugin.json").writeText(
            """{"name":"my-plugin","version":"1.0.0","description":"D","skills":["skills"]}""",
        )
        File(root, "skills").mkdirs()
        File(root, "skills/one").mkdirs()
        File(root, "skills/one/SKILL.md").writeText("---\nname: one\ndescription: d\n---\n\nbody\n")
        val adapted = PluginFormats.adapt(root, "fallback")
        assertEquals("Claude Code plugin", adapted.manifest.format)
        assertEquals(listOf("skills"), adapted.skillDirs)
    }

    @Test
    fun adaptsCodexAndCursorPlugins() {
        for ((dir, label) in listOf(".codex-plugin" to "Codex plugin", ".cursor-plugin" to "Cursor plugin")) {
            val root = tmp.newFolder(dir.removePrefix("."))
            File(root, dir).mkdirs()
            File(root, "$dir/plugin.json").writeText("""{"name":"x","version":"1","skills":["skills"]}""")
            File(root, "skills").mkdirs()
            val adapted = PluginFormats.adapt(root, "fallback")
            assertEquals(label, adapted.manifest.format)
        }
    }

    @Test
    fun adaptsGeminiExtension() {
        val root = tmp.newFolder("gemini")
        File(root, "gemini-extension.json").writeText(
            """{"name":"gem","version":"1","description":"G",
               "mcpServers":{"search":{"command":"npx","args":["-y","@modelcontextprotocol/server-brave-search"]}}}""",
        )
        val adapted = PluginFormats.adapt(root, "fallback")
        assertEquals("Gemini CLI extension", adapted.manifest.format)
        assertEquals(1, adapted.mcpServers.size)
        assertEquals(McpTransport.STDIO, adapted.mcpServers.first().transport)
        assertTrue(adapted.mcpServers.first().command.contains("npx"))
    }

    @Test
    fun adaptsGenericSkillCollection() {
        val root = tmp.newFolder("generic")
        File(root, "one").mkdirs()
        File(root, "one/SKILL.md").writeText("---\nname: one\ndescription: d\n---\n\nbody\n")
        val adapted = PluginFormats.adapt(root, "my-collection")
        assertEquals("Skill collection", adapted.manifest.format)
        assertEquals(1, adapted.skillDirs.size)
        assertTrue(adapted.skillDirs.first().endsWith("one"))
    }

    @Test
    fun nativeManifestWithHttpMcpConvertsTransport() {
        val root = tmp.newFolder("http")
        File(root, "plugin.json").writeText(
            """{"name":"x","version":"1","mcp":{"remote":{"url":"https://example.com/mcp"}}}""",
        )
        val adapted = PluginFormats.adapt(root, "fallback")
        assertEquals(1, adapted.mcpServers.size)
        assertEquals(McpTransport.HTTP, adapted.mcpServers.first().transport)
    }

    @Test
    fun sanitizeLowercasesAndHyphenates() {
        val root = tmp.newFolder("empty")
        File(root, "plugin.json").writeText("""{"name":"My Plugin!","version":"1","skills":[]}""")
        val adapted = PluginFormats.adapt(root, "fallback")
        assertEquals("my-plugin", adapted.manifest.id)
    }
}
