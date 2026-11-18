package com.mobileharness.code.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    private fun manifest(
        versionCode: Long = 6,
        versionName: String = "1.2.2",
        url: String = "https://github.com/Konprostart/mobile-harness-code/releases/download/v1.2.2/mobile-harness-online.apk",
        sha256: String = "abc123",
        sizeBytes: Long = 89_881_587L,
        notes: String = "notes",
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "notes": "$notes",
          "artifacts": {
            "online": {
              "url": "$url",
              "sha256": "$sha256",
              "sizeBytes": $sizeBytes
            }
          }
        }
    """.trimIndent()

    @Test
    fun returnsNullWhenRemoteIsNotNewer() {
        assertNull(parseAppUpdateManifest(manifest(versionCode = 1), currentVersionCode = 1, variant = "online"))
        assertNull(parseAppUpdateManifest(manifest(versionCode = 1), currentVersionCode = 2, variant = "online"))
    }

    @Test
    fun returnsInfoWhenRemoteIsNewer() {
        val info = parseAppUpdateManifest(manifest(versionCode = 6), currentVersionCode = 5, variant = "online")
        assertEquals(6L, info?.versionCode)
        assertEquals("1.2.2", info?.versionName)
        assertEquals("notes", info?.notes)
        assertEquals(89_881_587L, info?.sizeBytes)
    }

    @Test
    fun resolvesArtifactForRequestedVariant() {
        val json = """
            {
              "versionCode": 6,
              "versionName": "1.2.2",
              "artifacts": {
                "online": { "url": "https://example.com/online.apk", "sha256": "A", "sizeBytes": 1 },
                "offline": { "url": "https://example.com/offline.apk", "sha256": "B", "sizeBytes": 2 }
              }
            }
        """.trimIndent()
        val online = parseAppUpdateManifest(json, 5, "online")
        assertEquals("https://example.com/online.apk", online?.apkUrl)
        val offline = parseAppUpdateManifest(json, 5, "offline")
        assertEquals("https://example.com/offline.apk", offline?.apkUrl)
    }

    @Test
    fun fallsBackToRootArtifactWhenNoArtifactsBlock() {
        val json = """
            {
              "versionCode": 6,
              "versionName": "1.2.2",
              "url": "https://example.com/root.apk",
              "sha256": "abc",
              "sizeBytes": 42
            }
        """.trimIndent()
        val info = parseAppUpdateManifest(json, 5, "online")
        assertEquals("https://example.com/root.apk", info?.apkUrl)
        assertEquals("abc", info?.sha256)
        assertEquals(42L, info?.sizeBytes)
    }

    @Test
    fun rejectsInsecureUrls() {
        assertNull(parseAppUpdateManifest(manifest(url = "http://example.com/x.apk"), 5, "online"))
        assertNull(parseAppUpdateManifest(manifest(url = "file:///sdcard/x.apk"), 5, "online"))
        assertNull(parseAppUpdateManifest(manifest(url = ""), 5, "online"))
    }

    @Test
    fun lowercasesSha256() {
        val info = parseAppUpdateManifest(manifest(sha256 = "ABC123DEF"), 5, "online")
        assertEquals("abc123def", info?.sha256)
    }

    @Test
    fun defaultsVersionNameToVersionCode() {
        val json = """
            {
              "versionCode": 6,
              "artifacts": {
                "online": { "url": "https://example.com/x.apk" }
              }
            }
        """.trimIndent()
        val info = parseAppUpdateManifest(json, 5, "online")
        assertEquals("6", info?.versionName)
    }

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateChecksum.of(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateChecksum.of("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun sha256OfFileMatchesSha256OfBytes() {
        val payload = "mobile harness code update checksum test\n".repeat(100).toByteArray(Charsets.UTF_8)
        val file = File.createTempFile("update", ".bin")
        try {
            file.writeBytes(payload)
            assertEquals(UpdateChecksum.of(payload), UpdateChecksum.of(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun sha256OfFileIsHexAndLowercase() {
        val file = File.createTempFile("update", ".bin")
        try {
            file.writeText("hello")
            val hash = UpdateChecksum.of(file)
            assertEquals(64, hash.length)
            assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        } finally {
            file.delete()
        }
    }
}
