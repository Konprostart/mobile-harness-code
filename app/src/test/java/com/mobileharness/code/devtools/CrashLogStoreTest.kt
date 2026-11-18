package com.mobileharness.code.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashLogStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("crashlogs-test").toFile()

    private fun record(id: String, timestamp: Long) = CrashRecord(
        id = id,
        timestampMillis = timestamp,
        appVersion = "1.2.4",
        threadName = "main",
        throwableSummary = "java.lang.RuntimeException: boom-$id",
        stackTrace = "java.lang.RuntimeException: boom-$id\n    at A.b()",
    )

    @Test
    fun roundTripPreservesRecord() {
        val dir = tempDir()
        CrashLogStore.write(dir, record("abc", 1000L))
        val read = CrashLogStore.read(dir)
        assertEquals(1, read.size)
        assertEquals("abc", read[0].id)
        assertEquals("java.lang.RuntimeException: boom-abc", read[0].throwableSummary)
        assertTrue(read[0].stackTrace.startsWith("java.lang.RuntimeException: boom-abc"))
    }

    @Test
    fun readsNewestFirst() {
        val dir = tempDir()
        CrashLogStore.write(dir, record("1", 1000L))
        CrashLogStore.write(dir, record("2", 3000L))
        CrashLogStore.write(dir, record("3", 2000L))
        val read = CrashLogStore.read(dir)
        assertEquals(listOf("2", "3", "1"), read.map { it.id })
    }

    @Test
    fun evictsOldestBeyondMaxRecords() {
        val dir = tempDir()
        for (i in 0 until CrashLogStore.MAX_RECORDS + 5) {
            CrashLogStore.write(dir, record("id$i", (i + 1).toLong()))
        }
        val read = CrashLogStore.read(dir)
        assertEquals(CrashLogStore.MAX_RECORDS, read.size)
        // Newest entry survives, oldest entries are evicted.
        assertEquals("id${CrashLogStore.MAX_RECORDS + 4}", read.first().id)
        assertFalse(read.any { it.id == "id0" })
    }

    @Test
    fun clearRemovesAllRecords() {
        val dir = tempDir()
        CrashLogStore.write(dir, record("a", 1000L))
        CrashLogStore.write(dir, record("b", 2000L))
        CrashLogStore.clear(dir)
        assertTrue(CrashLogStore.read(dir).isEmpty())
    }

    @Test
    fun writeToEmptyDirectoryIsSafe() {
        val dir = tempDir()
        assertTrue(CrashLogStore.read(dir).isEmpty())
        CrashLogStore.clear(dir)
        assertTrue(CrashLogStore.read(dir).isEmpty())
    }

    @Test
    fun pruneStaleRemovesOnlyOldRecords() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        val stale = now - CrashLogStore.MAX_AGE_MILLIS - 60_000L
        CrashLogStore.write(dir, record("old", stale))
        CrashLogStore.write(dir, record("fresh", now))
        CrashLogStore.pruneStale(dir)
        val read = CrashLogStore.read(dir)
        assertEquals(listOf("fresh"), read.map { it.id })
    }

    @Test
    fun pruneStaleWithCustomWindow() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        CrashLogStore.write(dir, record("a", now - 10_000L))
        CrashLogStore.write(dir, record("b", now - 200_000L))
        CrashLogStore.pruneStale(dir, maxAgeMillis = 100_000L)
        val read = CrashLogStore.read(dir)
        assertEquals(listOf("a"), read.map { it.id })
    }
}
