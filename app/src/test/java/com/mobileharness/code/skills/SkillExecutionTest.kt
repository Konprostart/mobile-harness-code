package com.mobileharness.code.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillExecutionTest {

    private fun skill(body: String = "Analyze this", fields: Map<String, String> = emptyMap()) = InstalledSkill(
        id = "test", name = "test", description = "desc", category = "general",
        body = body, raw = "---\nname: test\ndescription: desc\n---\n\n$body\n",
        fields = fields, source = SkillSource.USER, sourceRef = "local", relativeDir = "test",
    )

    @Test
    fun renderBodySubstitutesArguments() {
        val body = "Review ${'$'}ARGUMENTS"
        assertEquals("Review foo bar", SkillExecution.renderBody(skill(body), "foo bar"))
    }

    @Test
    fun buildInstructionIncludesNameAndDescription() {
        val block = SkillExecution.buildInstruction(skill(), "args")
        assertTrue(block.contains("[SKILL: test]"))
        assertTrue(block.contains("desc"))
        assertTrue(block.contains("Analyze this"))
    }

    @Test
    fun shellPlanBlocksAllWhenCannotExec() {
        val plan = SkillExecution.shellPlan(skill("Run !`ls -la`"), canExec = false)
        assertEquals(listOf("ls -la"), plan.all)
        assertEquals(emptyList<String>(), plan.allowed)
        assertEquals(listOf("ls -la"), plan.blocked)
    }

    @Test
    fun shellPlanAllowsOnlyAllowedTools() {
        val body = "Run !`npm test` and !`rm -rf /`"
        val fields = mapOf("allowed-tools" to "Bash(npm *)")
        val plan = SkillExecution.shellPlan(skill(body, fields), canExec = true)
        assertEquals(listOf("npm test"), plan.allowed)
        assertEquals(listOf("rm -rf /"), plan.blocked)
    }

    @Test
    fun fileReferencesExtracted() {
        assertEquals(listOf("scripts/a.py"), SkillExecution.fileReferences(skill("Read @scripts/a.py")))
    }

    @Test
    fun isCommandDetected() {
        assertTrue(SkillExecution.isCommand(skill("x \$ARGUMENTS")))
        assertFalse(SkillExecution.isCommand(skill("plain")))
    }
}
