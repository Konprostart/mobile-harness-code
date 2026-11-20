package com.mobileharness.code.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCodecTest {
    @Test
    fun roundTripPreservesSkills() {
        val skills = listOf(
            Skill("a", "Alpha", "desc", "Do alpha", enabled = true),
            Skill("b", "Beta", "", "Do beta", enabled = false),
        )
        val decoded = SkillCodec.fromJson(SkillCodec.toJson(skills))
        assertEquals(skills, decoded)
    }

    @Test
    fun blankJsonReturnsEmpty() {
        assertEquals(emptyList<Skill>(), SkillCodec.fromJson(""))
        assertEquals(emptyList<Skill>(), SkillCodec.fromJson("   "))
    }

    @Test
    fun malformedJsonReturnsEmpty() {
        assertEquals(emptyList<Skill>(), SkillCodec.fromJson("{not json"))
        assertEquals(emptyList<Skill>(), SkillCodec.fromJson("[1,2,3]"))
    }

    @Test
    fun invalidEntriesAreDropped() {
        val json = """[{"id":"","name":"X","description":"","instructions":"y"}]"""
        assertEquals(emptyList<Skill>(), SkillCodec.fromJson(json))
    }

    @Test
    fun oversizedInstructionsAreDropped() {
        val huge = "x".repeat(SkillPolicy.MAX_INSTRUCTIONS_CHARS + 1)
        val skill = Skill("id", "name", "", huge)
        assertTrue(!SkillPolicy.isValid(skill))
        assertEquals(emptyList<Skill>(), SkillCodec.fromJson(SkillCodec.toJson(listOf(skill))))
    }
}
