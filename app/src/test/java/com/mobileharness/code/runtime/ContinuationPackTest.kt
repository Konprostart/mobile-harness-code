package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuationPackTest {

    @Test
    fun instructionContainsContinueGuard() {
        val pack = ContinuationPack(task = "build the app")
        val instruction = pack.toInstruction()
        assertTrue(instruction.contains("Continue the same task from where it stopped."))
        assertTrue(instruction.contains("Do not repeat operations that already have results."))
    }

    @Test
    fun inFlightLastToolAddsVerifyGuard() {
        val pack = ContinuationPack(
            task = "build the app",
            lastTool = LastTool("Bash", LastToolStatus.IN_FLIGHT),
        )
        val instruction = pack.toInstruction()
        assertTrue(instruction.contains("verify whether it already happened"))
        assertTrue(instruction.contains("in_flight"))
    }

    @Test
    fun doneLastToolOmitsVerifyGuard() {
        val pack = ContinuationPack(
            task = "build the app",
            lastTool = LastTool("Bash", LastToolStatus.DONE),
        )
        val instruction = pack.toInstruction()
        assertFalse(instruction.contains("verify whether it already happened"))
        assertTrue(instruction.contains("done"))
    }

    @Test
    fun builderMarksInFlightOnlyWhenToolsExecuted() {
        val interrupted = ContinuationPackBuilder.forInterruptedTask("task", toolsExecuted = true)
        assertNotNull(interrupted.lastTool)
        assertEquals(LastToolStatus.IN_FLIGHT, interrupted.lastTool!!.status)

        val clean = ContinuationPackBuilder.forInterruptedTask("task", toolsExecuted = false)
        assertEquals(null, clean.lastTool)
    }

    @Test
    fun instructionIncludesTouchedFilesAndDiff() {
        val pack = ContinuationPack(
            task = "task",
            touchedFiles = listOf("src/Main.kt", "README.md"),
            gitDiffStat = "2 files changed",
        )
        val instruction = pack.toInstruction()
        assertTrue(instruction.contains("- src/Main.kt"))
        assertTrue(instruction.contains("- README.md"))
        assertTrue(instruction.contains("2 files changed"))
    }

    @Test
    fun instructionNeverEmbedsSecrets() {
        // The pack is a plain handoff; it must never serialize anything that looks like a key.
        val pack = ContinuationPack(task = "task", summary = "no secrets here")
        val instruction = pack.toInstruction()
        assertFalse(instruction.contains("sk-"))
        assertFalse(instruction.contains("ANTHROPIC_API_KEY"))
        assertFalse(instruction.contains("Authorization"))
    }
}
