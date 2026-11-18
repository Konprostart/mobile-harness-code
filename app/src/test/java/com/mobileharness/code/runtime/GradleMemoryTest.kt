package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleMemoryTest {
    @Test
    fun jvmArgsAlwaysPinHeapAndMetaspace() {
        val args = GradleMemoryProfile(1536, 768).jvmArgs
        assertEquals("-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8", args)
    }

    @Test
    fun recommendedScalesWithDeviceRam() {
        assertEquals(GradleMemoryProfile(768, 256), GradleMemory.recommendedForRamMb(2048))
        assertEquals(GradleMemoryProfile(1024, 384), GradleMemory.recommendedForRamMb(3072))
        assertEquals(GradleMemoryProfile(1536, 768), GradleMemory.recommendedForRamMb(4096))
        assertEquals(GradleMemoryProfile(2048, 1024), GradleMemory.recommendedForRamMb(8192))
    }

    @Test
    fun applyJvmArgsInsertsWhenAbsent() {
        val content = "android.useAndroidX=true\nkotlin.code.style=official\n"
        val updated = GradleMemory.applyJvmArgs(content, GradleMemoryProfile(1536, 768))
        assertTrue(updated.contains("org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"))
        assertTrue(updated.contains("android.useAndroidX=true"))
    }

    @Test
    fun applyJvmArgsReplacesExistingLineInPlace() {
        val content = "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m\nkotlin.code.style=official\n"
        val updated = GradleMemory.applyJvmArgs(content, GradleMemoryProfile(1024, 384))
        assertEquals(
            "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8\nkotlin.code.style=official\n",
            updated,
        )
    }

    @Test
    fun applyJvmArgsPreservesUnrelatedFormatting() {
        val content = "# a comment\n\norg.gradle.jvmargs=-Xmx1g\nandroid.nonTransitiveRClass=true"
        val updated = GradleMemory.applyJvmArgs(content, GradleMemoryProfile(1536, 768))
        assertTrue(updated.startsWith("# a comment\n\norg.gradle.jvmargs=-Xmx1536m"))
        assertTrue(updated.endsWith("android.nonTransitiveRClass=true\n"))
    }
}
