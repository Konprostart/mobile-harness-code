package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optional Deno runtime is installed from the official denoland/deno release
 * (MIT) as a direct ARM64 download, not from apt. These tests pin the exact
 * release URL, SHA-256, guest paths and the enable/disable symlink layout so a
 * future edit cannot silently change them (or pull in an apt package).
 */
class RuntimeInstallerDenoTest {

    @Test
    fun releaseIsPinnedAndArm64Glibc() {
        assertEquals("2.9.8", RuntimeInstaller.DENO_VERSION)
        assertTrue(
            "Deno must download the aarch64 glibc build",
            RuntimeInstaller.DENO_RELEASE_URL.contains("aarch64-unknown-linux-gnu"),
        )
        assertTrue(
            "The download must be a zip archive",
            RuntimeInstaller.DENO_RELEASE_URL.endsWith(".zip"),
        )
        assertTrue(
            "The release URL must include the pinned version",
            RuntimeInstaller.DENO_RELEASE_URL.contains("/v${RuntimeInstaller.DENO_VERSION}/"),
        )
    }

    @Test
    fun sha256IsA64HexDigest() {
        val digest = RuntimeInstaller.DENO_RELEASE_SHA256
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" })
        assertEquals(
            "d49596c20b9788cf8f5d8ddf644103d4bde56ed239753f0bdb92ba9361d51905",
            digest,
        )
    }

    @Test
    fun binaryLivesOutsideActivePath() {
        // The binary must sit outside the guest PATH so "installed" never implies
        // "enabled". Only the symlink in /usr/local/bin exposes it.
        assertEquals("/opt/deno/deno", RuntimeInstaller.DENO_GUEST_PATH)
        assertFalse("binary must not live under /usr/bin", RuntimeInstaller.DENO_GUEST_PATH.startsWith("/usr/bin"))
        assertFalse("binary must not live under /usr/local/bin", RuntimeInstaller.DENO_GUEST_PATH.startsWith("/usr/local/bin"))
    }

    @Test
    fun symlinkIsTheEnableSwitchAndResolvesToBinary() {
        assertEquals("/usr/local/bin/deno", RuntimeInstaller.DENO_SYMLINK_PATH)
        assertEquals("../../../opt/deno/deno", RuntimeInstaller.DENO_SYMLINK_TARGET)
        // From /usr/local/bin, the relative target must resolve to /opt/deno/deno.
        val resolved = java.io.File(
            java.io.File(RuntimeInstaller.DENO_SYMLINK_PATH).parentFile,
            RuntimeInstaller.DENO_SYMLINK_TARGET,
        ).canonicalPath.replace('\\', '/')
        assertTrue(
            "symlink target must resolve to the Deno binary path",
            resolved.endsWith(RuntimeInstaller.DENO_GUEST_PATH),
        )
    }

    @Test
    fun cacheIsSeparateFromBinaryAndNeverUnderUsr() {
        assertEquals("/root/.cache/deno", RuntimeInstaller.DENO_DIR_GUEST_PATH)
        assertFalse(RuntimeInstaller.DENO_DIR_GUEST_PATH.startsWith("/usr"))
        assertFalse(
            "cache must be a distinct directory from the binary",
            RuntimeInstaller.DENO_DIR_GUEST_PATH == RuntimeInstaller.DENO_GUEST_PATH,
        )
    }
}
