package com.mobileharness.code.skills

import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSkillsTest {
    @Test
    fun defaultsAreUniqueValidAndDisabled() {
        val skills = BuiltinSkills.defaults()
        assertTrue(skills.isNotEmpty())
        val ids = skills.map { it.id }
        assertTrue(ids.size == ids.toSet().size)
        skills.forEach { skill ->
            assertTrue(SkillPolicy.isValid(skill))
            assertTrue(!skill.enabled)
        }
    }
}
