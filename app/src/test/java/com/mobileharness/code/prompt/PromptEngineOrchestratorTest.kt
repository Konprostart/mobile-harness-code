package com.mobileharness.code.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PromptEngineOrchestratorTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("auto-prompt-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- PromptEngine orchestration ------------------------------------------------

    @Test
    fun `assemble produces and versions a prompt`() {
        val engine = PromptEngine()
        val assembled = engine.assemble(
            PromptEngine.Request(
                task = "Add a feature",
                agentKind = "claude",
                projectId = "p1",
                agentsMd = "# Style\nUse Kotlin.\n",
            ),
        )
        assertNotNull(assembled)
        assertTrue(assembled!!.developer.contains("p1"))
        assertEquals(1, engine.versionCount())
        assertEquals(assembled.versionId, engine.latestVersion()!!.id)
    }

    @Test
    fun `assemble is fail-closed on blank task`() {
        val engine = PromptEngine()
        assertNull(engine.assemble(PromptEngine.Request(task = "   ", agentKind = "claude")))
        assertEquals(0, engine.versionCount())
    }

    @Test
    fun `buildInstructions orders workspace rules before agents md`() {
        val engine = PromptEngine()
        val instructions = engine.buildInstructions(
            agentsMd = "# Style\nUse spaces.\n",
            workspaceRules = listOf("Use tabs."),
        )
        assertTrue(instructions.first().source == InstructionSource.WORKSPACE_RULES)
        assertTrue(instructions.last().source == InstructionSource.AGENTS_MD)
    }

    @Test
    fun `readAgentsMd reads a workspace AGENTS file`() {
        val engine = PromptEngine()
        withTempDir { dir ->
            File(dir, "AGENTS.md").writeText("# Style\nUse Kotlin.\n")
            val content = engine.readAgentsMd(dir)
            assertNotNull(content)
            assertTrue(content!!.contains("Use Kotlin."))
        }
    }

    @Test
    fun `readAgentsMd prefers CLAUDE md when AGENTS absent`() {
        val engine = PromptEngine()
        withTempDir { dir ->
            File(dir, "CLAUDE.md").writeText("# Rule\nBe brief.\n")
            val content = engine.readAgentsMd(dir)
            assertNotNull(content)
            assertTrue(content!!.contains("Be brief."))
        }
    }

    @Test
    fun `readAgentsMd returns null when absent`() {
        val engine = PromptEngine()
        withTempDir { dir -> assertNull(engine.readAgentsMd(dir)) }
        assertNull(engine.readAgentsMd(null))
    }

    @Test
    fun `guidanceBlock renders data block and null when empty`() {
        val engine = PromptEngine()
        val block = engine.guidanceBlock("# Style\nUse Kotlin.\n")
        assertNotNull(block)
        assertTrue(block!!.contains("<workspace_instructions>"))
        assertTrue(block.contains("Use Kotlin."))
        assertTrue(block.contains("data"))
        assertNull(engine.guidanceBlock(null))
        assertNull(engine.guidanceBlock(""))
    }

    // --- AutoPrompt on/off (fail-closed) --------------------------------------------

    @Test
    fun `auto prompt disabled returns prompt unchanged`() {
        val engine = PromptEngine()
        val original = "System context\n\nuser task"
        val result = AutoPrompt.apply(
            contextPrompt = original,
            rawTask = "user task",
            enabled = false,
            engine = engine,
            workspaceDir = null,
            agentKind = "claude",
            projectId = null,
        )
        assertEquals(original, result)
        assertEquals(0, engine.versionCount())
    }

    @Test
    fun `auto prompt enabled prepends guidance from workspace`() {
        val engine = PromptEngine()
        withTempDir { dir ->
            File(dir, "AGENTS.md").writeText("# Style\nUse Kotlin.\n")
            val original = "System context\n\nuser task"
            val result = AutoPrompt.apply(
                contextPrompt = original,
                rawTask = "user task",
                enabled = true,
                engine = engine,
                workspaceDir = dir,
                agentKind = "claude",
                projectId = "p1",
            )
            assertTrue(result.startsWith("<workspace_instructions>"))
            assertTrue(result.contains("Use Kotlin."))
            assertTrue(result.endsWith(original))
            assertEquals(1, engine.versionCount())
        }
    }

    @Test
    fun `auto prompt enabled but no agents file returns unchanged`() {
        val engine = PromptEngine()
        withTempDir { dir ->
            val original = "System context\n\nuser task"
            val result = AutoPrompt.apply(
                contextPrompt = original,
                rawTask = "user task",
                enabled = true,
                engine = engine,
                workspaceDir = dir,
                agentKind = "claude",
                projectId = null,
            )
            assertEquals(original, result)
            // The full assembly is still versioned even though nothing was injected.
            assertEquals(1, engine.versionCount())
        }
    }

    @Test
    fun `auto prompt enabled with blank raw task still injects guidance`() {
        val engine = PromptEngine()
        withTempDir { dir ->
            File(dir, "AGENTS.md").writeText("# Rule\nBe brief.\n")
            val original = "context"
            val result = AutoPrompt.apply(
                contextPrompt = original,
                rawTask = "   ",
                enabled = true,
                engine = engine,
                workspaceDir = dir,
                agentKind = "dsh",
                projectId = null,
            )
            assertTrue(result.contains("Be brief."))
            // Blank task means assemble() fails closed → no version recorded.
            assertEquals(0, engine.versionCount())
            assertFalse(result.startsWith(original))
        }
    }
}
