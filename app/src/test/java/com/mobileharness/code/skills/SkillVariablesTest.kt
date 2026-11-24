package com.mobileharness.code.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillVariablesTest {

    @Test
    fun expandsKnownVariables() {
        val text = "Run ${'$'}{CLAUDE_SKILL_DIR}/scripts/x.sh"
        val out = SkillVariables.expand(text, "/skills/my-skill", null, null)
        assertEquals("Run /skills/my-skill/scripts/x.sh", out)
    }

    @Test
    fun leavesUnknownVariablesAlone() {
        val text = "Value ${'$'}{CLAUDE_UNKNOWN}"
        assertEquals(text, SkillVariables.expand(text, null, null, null))
    }

    @Test
    fun argumentsSubstitutesPlaceholders() {
        val body = "Analyze ${'$'}ARGUMENTS with ${'$'}1 and ${'$'}2"
        val out = CommandTemplate.arguments(body, "foo bar baz")
        assertEquals("Analyze foo bar baz with foo and bar", out)
    }

    @Test
    fun argumentsAppendsWhenNoPlaceholder() {
        val body = "Do the thing"
        val out = CommandTemplate.arguments(body, "extra args")
        assertTrue(out.contains("ARGUMENTS: extra args"))
    }

    @Test
    fun geminiArgsSubstituted() {
        val body = "Use {{args}}"
        assertEquals("Use hi there", CommandTemplate.arguments(body, "hi there"))
    }

    @Test
    fun wordsHonorsQuotes() {
        assertEquals(listOf("a", "b c", "d"), CommandTemplate.words("a \"b c\" d"))
    }

    @Test
    fun extractsShellCommands() {
        val text = "Run !`ls -la` then !{cat file.txt}"
        assertEquals(listOf("ls -la", "cat file.txt"), CommandTemplate.shellCommands(text))
    }

    @Test
    fun extractsFileReferences() {
        val text = "Read @path/to/a.md and @{path/to/b.md} but not @/abs.md"
        assertEquals(setOf("path/to/a.md", "path/to/b.md"), CommandTemplate.fileReferences(text).toSet())
    }

    @Test
    fun rejectsParentTraversalInFileReferences() {
        val text = "Read @../secret.md"
        assertTrue(CommandTemplate.fileReferences(text).isEmpty())
    }

    @Test
    fun shellAllowedRules() {
        assertTrue(CommandTemplate.shellAllowed("Bash(*)", "npm test"))
        assertTrue(CommandTemplate.shellAllowed("Bash(npm *)", "npm test"))
        assertTrue(CommandTemplate.shellAllowed("Bash(npm test)", "npm test"))
        assertFalse(CommandTemplate.shellAllowed("Bash(npm test)", "npm build"))
        assertFalse(CommandTemplate.shellAllowed(null, "ls"))
    }

    @Test
    fun withShellOutputReplacesCommands() {
        val text = "Run !`ls`"
        assertEquals("Run [output]", CommandTemplate.withShellOutput(text) { "[output]" })
    }

    @Test
    fun appliesDetectsCommandsAndArgs() {
        assertTrue(CommandTemplate.applies(emptyMap(), "x \$ARGUMENTS"))
        assertTrue(CommandTemplate.applies(mapOf("command" to "true"), "no placeholders"))
        assertFalse(CommandTemplate.applies(emptyMap(), "plain body"))
    }
}
