package com.mobileharness.code.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionDetailTest {

    @Test
    fun detailedCompactionReportsSizes() {
        val blocks = listOf(
            ContextBlock(ContextPriority.SYSTEM_INSTRUCTIONS, "AAAA", fixed = true),
            ContextBlock(ContextPriority.IMPORTANT_DECISIONS, "BBBBBBBB"),
            ContextBlock(ContextPriority.BACKGROUND, "CCCCCCCC"),
        )
        val result = TokenContextCompactor.compactDetailed(blocks, maxChars = 8)!!
        assertTrue(result.succeeded)
        assertEquals(4 + 8 + 8, result.originalChars)
        assertTrue(result.removedChars > 0)
        assertTrue(result.compactedChars <= 8)
        // System instruction preserved verbatim.
        assertTrue(result.blocks.any { it.fixed && it.text == "AAAA" })
    }

    @Test
    fun detailedCompactionReturnsNullWhenBudgetTooSmall() {
        val blocks = listOf(ContextBlock(ContextPriority.SYSTEM_INSTRUCTIONS, "ABCDEFGHIJ", fixed = true))
        assertNull(TokenContextCompactor.compactDetailed(blocks, maxChars = 5))
    }

    @Test
    fun summarizeReportsHonestOutcome() {
        val success = TokenContextCompactor.compactDetailed(
            listOf(
                ContextBlock(ContextPriority.SYSTEM_INSTRUCTIONS, "AAAA", fixed = true),
                ContextBlock(ContextPriority.BACKGROUND, "BBBBBBBB"),
            ),
            maxChars = 6,
        )!!
        val summary = TokenContextCompactor.summarize(success)
        assertTrue(summary.contains("Removed"))
        assertFalse(summary.contains("aborted"))
    }
}
