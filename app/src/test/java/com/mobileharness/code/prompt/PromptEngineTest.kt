package com.mobileharness.code.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptEngineTest {

    @Test
    fun `assembler dedupes conflicting instructions by priority`() {
        val instructions = listOf(
            Instruction(InstructionSource.WORKSPACE_RULES, "Use tabs for indentation", key = "indent"),
            Instruction(InstructionSource.AGENTS_MD, "Use spaces for indentation", key = "indent"),
        )
        val deduped = PromptAssembler.dedupe(instructions)
        assertEquals(1, deduped.size)
        assertEquals("Use tabs for indentation", deduped.single().text)
    }

    @Test
    fun `contradiction detection`() {
        val a = Instruction(InstructionSource.AGENTS_MD, "use tabs", key = "indent")
        val b = Instruction(InstructionSource.SKILL, "use spaces", key = "indent")
        assertTrue(PromptAssembler.contradicts(a, b))
    }

    @Test
    fun `assemble includes system and developer context`() {
        val context = PromptContext(
            task = "Add a feature",
            projectId = "p1",
            workspacePath = "/workspace/p1",
            repoSummary = "Kotlin Compose app",
        )
        val instructions = listOf(Instruction(InstructionSource.SYSTEM, "You are a coding agent."))
        val assembled = PromptAssembler.assemble(context, instructions)
        assertTrue(assembled.system.contains("coding agent"))
        assertTrue(assembled.developer.contains("p1"))
        assertEquals("Add a feature", assembled.task)
        assertNotNull(assembled.versionId)
    }

    @Test
    fun `token budget truncates`() {
        val long = "a".repeat(10_000)
        val fitted = PromptAssembler.fitBudget(long, 100)
        assertTrue(fitted.length < long.length)
        assertTrue(fitted.contains("truncated"))
    }

    @Test
    fun `task decomposer splits on rule delimiter`() {
        val task = "Step one\n---\nStep two\n---\nStep three"
        val subtasks = TaskDecomposer.decompose(task)
        assertEquals(3, subtasks.size)
        assertEquals(listOf("0"), subtasks[1].dependsOn)
        assertEquals(listOf("1"), subtasks[2].dependsOn)
    }

    @Test
    fun `task decomposer falls back to single task`() {
        val subtasks = TaskDecomposer.decompose("Just one task")
        assertEquals(1, subtasks.size)
        assertEquals("Just one task", subtasks.single().prompt)
    }

    @Test
    fun `version store keeps latest and restores by id`() {
        val store = PromptVersionStore(maxVersions = 3)
        repeat(4) {
            store.save(
                AssembledPrompt("s$it", "d", "t$it", emptyList(), "id-$it"),
            )
        }
        assertEquals(3, store.list().size)
        assertEquals("t3", store.latest()!!.task)
        assertEquals("id-2", store.get("id-2")!!.id)
        assertEquals(null, store.get("id-0"))
    }

    @Test
    fun `version store json round trip`() {
        val store = PromptVersionStore()
        store.save(AssembledPrompt("sys", "dev", "task", emptyList(), "v1"))
        val restored = PromptVersionStore()
        restored.load(store.toJson())
        assertEquals("task", restored.latest()!!.task)
    }

    @Test
    fun `agents md parser extracts headings`() {
        val md = """
            # Style
            Use Kotlin idiomatic code.

            ## Tests
            Every module needs a test.
        """.trimIndent()
        val instructions = AgentsMdParser.parse(md)
        assertEquals(2, instructions.size)
        assertTrue(instructions.any { it.text.startsWith("Style:") })
        assertTrue(instructions.any { it.text.startsWith("Tests:") })
    }

    @Test
    fun `agents md find directive`() {
        val md = "language: Kotlin\nmodel: gpt-4o"
        assertEquals("Kotlin", AgentsMdParser.findDirective(md, "language"))
        assertEquals("gpt-4o", AgentsMdParser.findDirective(md, "MODEL"))
    }
}
