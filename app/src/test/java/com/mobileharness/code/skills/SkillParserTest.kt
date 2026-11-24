package com.mobileharness.code.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private fun skill(name: String = "my-skill", description: String = "Does something", body: String = "# Steps\nDo the thing") =
        "---\nname: $name\ndescription: $description\n---\n\n$body\n"

    @Test
    fun parsesFrontmatterAndBody() {
        val parsed = SkillParser.parse(skill())
        assertEquals("my-skill", parsed.name)
        assertEquals("Does something", parsed.description)
        assertEquals("general", parsed.category)
        assertTrue(parsed.body.contains("Do the thing"))
    }

    @Test
    fun parsesCategoryAndExtraFields() {
        val raw = "---\nname: my-skill\ndescription: d\ncategory: code\nversion: 1.2.3\nargument-hint: [file]\n---\n\nbody\n"
        val parsed = SkillParser.parse(raw)
        assertEquals("code", parsed.category)
        assertEquals("1.2.3", parsed.fields["version"])
        assertEquals("[file]", parsed.fields["argument-hint"])
    }

    @Test
    fun requiresFrontmatter() {
        var failed = false
        try { SkillParser.parse("just a body") } catch (e: SkillParseException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun rejectsMissingName() {
        var failed = false
        try { SkillParser.parse("---\ndescription: d\n---\n\nbody") } catch (e: SkillParseException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun rejectsPathTraversalInName() {
        var failed = false
        try { SkillParser.parse(skill(name = "../evil")) } catch (e: SkillParseException) { failed = true }
        assertTrue(failed)
        failed = false
        try { SkillParser.parse(skill(name = "a/b")) } catch (e: SkillParseException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun rejectsUppercaseName() {
        var failed = false
        try { SkillParser.parse(skill(name = "MySkill")) } catch (e: SkillParseException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun lenientFallsBackToFolderName() {
        val parsed = SkillParser.lenient("body without frontmatter", "Nice-Skill")
        assertEquals("nice-skill", parsed.name)
        assertTrue(parsed.description.isNotBlank())
    }

    @Test
    fun parsesBlockScalar() {
        val raw = "---\nname: s\ndescription: |\n  Line one.\n  Line two.\n---\n\nbody\n"
        val parsed = SkillParser.parse(raw)
        assertEquals("Line one.\nLine two.", parsed.description)
    }

    @Test
    fun safeNameLowercasesAndHyphenates() {
        assertEquals("my-skill", SkillParser.safeName("My Skill!"))
        assertEquals("skill", SkillParser.safeName("!!!%%%"))
    }

    @Test
    fun validateUserContentRejectsOversized() {
        val huge = skill(body = "x".repeat(ParsedSkill.MAX_USER_CHARS + 1))
        var failed = false
        try { SkillParser.validateUserContent(huge) } catch (e: SkillParseException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun catalogDescriptionTruncates() {
        val parsed = SkillParser.parse(skill(description = "x".repeat(200)))
        assertTrue(parsed.catalogDescription.length <= ParsedSkill.MAX_CATALOG_DESCRIPTION + 3)
        assertTrue(parsed.catalogDescription.endsWith("..."))
    }

    @Test
    fun nameRegexAllowsHyphensButNotUnderscores() {
        assertTrue(ParsedSkill.NAME_REGEX.matches("my-skill"))
        assertFalse(ParsedSkill.NAME_REGEX.matches("my_skill"))
        assertFalse(ParsedSkill.NAME_REGEX.matches("-leading"))
    }
}
