package com.mobileharness.code.token

/**
 * Protection priority for a context block. Higher numbers are protected first; a block with a
 * higher priority is never dropped or truncated before a lower-priority block.
 */
enum class ContextPriority(val rank: Int) {
    SYSTEM_INSTRUCTIONS(60),
    SAFETY_RULES(55),
    USER_INSTRUCTIONS(50),
    ACTIVE_TASKS(45),
    IMPORTANT_DECISIONS(40),
    RELEVANT_RESULTS(30),
    BACKGROUND(10),
}

/**
 * One unit of context the compactor reasons about. [priority] declares how important the block is;
 * [text] is its content. [fixed] blocks are never modified and must always fit verbatim.
 */
data class ContextBlock(
    val priority: ContextPriority,
    val text: String,
    val fixed: Boolean = false,
)

/**
 * Pure, priority-protected context compaction.
 *
 * It reduces a list of [ContextBlock]s to a character budget without ever touching the original
 * history (the caller keeps the original separately). The algorithm:
 *
 *  1. Fixed blocks (system instructions, safety rules) are always kept verbatim and subtracted
 *     from the budget first; if they alone exceed the budget the compaction *fails* (returns null)
 *     rather than corrupting them.
 *  2. Remaining blocks are kept in descending priority order; within a priority tier, each block is
 *     truncated to its fair share rather than dropped outright.
 *  3. A budget that cannot accommodate the fixed blocks aborts with null so the caller preserves
 *     the original context untouched.
 */
object TokenContextCompactor {
    fun compact(blocks: List<ContextBlock>, maxChars: Int): List<ContextBlock>? {
        if (maxChars <= 0) return null

        val fixed = blocks.filter { it.fixed }
        val fixedCost = fixed.sumOf { it.text.length }
        if (fixedCost > maxChars) return null

        val mutable = blocks.filterNot { it.fixed }
            .filter { it.text.isNotBlank() }
            .sortedByDescending { it.priority.rank }

        val result = mutableListOf<ContextBlock>()
        result.addAll(fixed)

        var remaining = maxChars - fixedCost
        val tiers = mutable.groupBy { it.priority }
        for (priority in tiers.keys.sortedByDescending { it.rank }) {
            val tierBlocks = tiers[priority]!!
            val fairShare = remaining / tierBlocks.size
            if (fairShare <= 0) break // no room for a lower tier without starving it entirely

            for (block in tierBlocks) {
                val kept = block.text.take(fairShare.coerceAtMost(remaining))
                if (kept.isEmpty()) continue
                result.add(ContextBlock(block.priority, kept, fixed = false))
                remaining -= kept.length
            }
        }
        // Preserve deterministic, stable ordering: fixed first, then by priority then original order.
        return result
    }

    /**
     * Convenience: compacts a simple list of priority/text pairs. Returns null on failure (budget
     * too small for the fixed blocks), so callers keep the original context unchanged.
     */
    fun compactOf(blocks: List<ContextBlock>, maxChars: Int, keepOriginalOnFailure: Boolean = true): List<ContextBlock> {
        return compact(blocks, maxChars) ?: if (keepOriginalOnFailure) blocks else emptyList()
    }

    /** Total characters in a compacted result, for validation in tests. */
    fun totalChars(blocks: List<ContextBlock>): Int = blocks.sumOf { it.text.length }
}
