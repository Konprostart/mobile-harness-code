package com.mobileharness.code.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestructiveOpClassifierTest {

    @Test
    fun `rm -rf is destructive`() {
        assertTrue(DestructiveOpClassifier.isDestructive(null, "rm -rf /tmp/cache"))
        assertTrue(DestructiveOpClassifier.isDestructive(null, "rm -fr build"))
    }

    @Test
    fun `git hard reset and force push are destructive`() {
        assertTrue(DestructiveOpClassifier.isDestructive(null, "git reset --hard HEAD~1"))
        assertTrue(DestructiveOpClassifier.isDestructive(null, "git push --force origin main"))
    }

    @Test
    fun `drop table is destructive`() {
        assertTrue(DestructiveOpClassifier.isDestructive(null, "DROP TABLE users"))
    }

    @Test
    fun `dd to a device is destructive`() {
        assertTrue(DestructiveOpClassifier.isDestructive(null, "dd if=img of=/dev/block/mmcblk0"))
    }

    @Test
    fun `structured delete key is destructive`() {
        assertTrue(DestructiveOpClassifier.isDestructive("file.delete", null))
        assertTrue(DestructiveOpClassifier.isDestructive("project.delete", null))
    }

    @Test
    fun `read-only commands are not destructive`() {
        assertFalse(DestructiveOpClassifier.isDestructive(null, "ls -la"))
        assertFalse(DestructiveOpClassifier.isDestructive(null, "cat file.txt"))
        assertFalse(DestructiveOpClassifier.isDestructive(null, "git status"))
    }

    @Test
    fun `risk level mapping`() {
        assertEquals(
            com.mobileharness.code.model.RiskLevel.HIGH,
            DestructiveOpClassifier.riskLevel(mutating = true, destructive = true),
        )
        assertEquals(
            com.mobileharness.code.model.RiskLevel.REVIEW,
            DestructiveOpClassifier.riskLevel(mutating = true, destructive = false),
        )
        assertEquals(
            com.mobileharness.code.model.RiskLevel.SAFE,
            DestructiveOpClassifier.riskLevel(mutating = false, destructive = false),
        )
    }
}
