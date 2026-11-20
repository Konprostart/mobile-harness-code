package com.mobileharness.code.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPolicyTest {
    private fun skill(id: String = "id", name: String = "Name", instructions: String = "Do the thing") =
        Skill(id, name, "desc", instructions)

    @Test
    fun validSkillPasses() {
        assertTrue(SkillPolicy.isValid(skill()))
    }

    @Test
    fun blankIdOrNameFails() {
        assertFalse(SkillPolicy.isValid(skill(id = "")))
        assertFalse(SkillPolicy.isValid(skill(name = "   ")))
    }

    @Test
    fun controlCharactersFail() {
        assertFalse(SkillPolicy.isValid(skill(name = "Bad\nName")))
        assertFalse(SkillPolicy.isValid(skill(instructions = "Do\u0000thing")))
    }

    @Test
    fun oversizedFieldsFail() {
        assertFalse(SkillPolicy.isValid(skill(name = "x".repeat(SkillPolicy.MAX_NAME_CHARS + 1))))
        assertFalse(SkillPolicy.isValid(skill(instructions = "x".repeat(SkillPolicy.MAX_INSTRUCTIONS_CHARS + 1))))
        assertFalse(SkillPolicy.isValid(skill(id = "id", name = "n").copy(description = "d".repeat(SkillPolicy.MAX_DESCRIPTION_CHARS + 1))))
    }

    @Test
    fun sanitizeBouncesAndTrims() {
        val sanitized = SkillPolicy.sanitize(skill(name = "  Name  ", instructions = "  Do  "))
        assertEquals("Name", sanitized.name)
        assertEquals("Do", sanitized.instructions)
    }

    @Test
    fun buildInstructionBlockOnlyIncludesEnabledValidSkills() {
        val skills = listOf(
            skill(id = "on", name = "On").copy(enabled = true),
            skill(id = "off", name = "Off").copy(enabled = false),
            skill(id = "bad", name = "").copy(enabled = true),
        )
        val block = SkillPolicy.buildInstructionBlock(skills)
        assertTrue(block.contains("On"))
        assertFalse(block.contains("Off"))
    }

    @Test
    fun buildInstructionBlockIsBlankWhenNothingEnabled() {
        val block = SkillPolicy.buildInstructionBlock(listOf(skill().copy(enabled = false)))
        assertEquals("", block)
    }
}
