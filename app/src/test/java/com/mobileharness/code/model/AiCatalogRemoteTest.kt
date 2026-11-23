package com.mobileharness.code.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCatalogRemoteTest {
    private fun remoteProvider(
        id: String,
        baseUrl: String = "https://api.example.com/v1",
        wireProtocol: AiWireProtocol = AiWireProtocol.OPENAI_CHAT,
        category: AiProviderCategory = AiProviderCategory.OFFICIAL,
        defaultModel: String = "model-1",
    ) = AiProvider(
        id = id, name = id, category = category, baseUrl = baseUrl,
        wireProtocol = wireProtocol, defaultModel = defaultModel, authType = AiAuthType.API_KEY,
        apiFormats = setOf(AiWireProtocol.OPENAI_CHAT),
        capabilities = setOf(AiCapability.CHAT_COMPLETIONS, AiCapability.STREAMING),
        docsUrl = "https://docs.example.com",
    )

    @Test
    fun codecRoundTripsProviders() {
        val providers = listOf(remoteProvider("example"), remoteProvider("example2"))
        val json = AiCatalogCodec.toJson(providers, version = 7L, updatedAt = "2026-01-01T00:00:00Z")
        val envelope = AiCatalogCodec.parse(json).getOrThrow()
        assertEquals(7L, envelope.version)
        assertEquals(2, envelope.providers.size)
        assertEquals("example", envelope.providers[0].id)
        assertEquals(AiAuthType.API_KEY, envelope.providers[0].authType)
    }

    @Test
    fun parseRejectsUnknownSchema() {
        val json = """{"schemaVersion":999,"providers":[]}"""
        assertTrue(AiCatalogCodec.parse(json).isFailure)
    }

    @Test
    fun parseRejectsUnknownEnum() {
        val json = """{"schemaVersion":1,"providers":[{"id":"x","name":"x","category":"NOT_A_CATEGORY"}]}"""
        assertTrue(AiCatalogCodec.parse(json).isFailure)
    }

    @Test
    fun validatorRejectsBadUrlsAndCredentials() {
        val bad = remoteProvider("bad", baseUrl = "http://evil.example.com/v1")
        assertTrue(AiCatalogValidator.validateRemote(listOf(bad)).any { it.contains("https://") })

        val creds = remoteProvider("creds", baseUrl = "https://user:pass@api.example.com/v1")
        assertTrue(AiCatalogValidator.validateRemote(listOf(creds)).any { it.contains("credentials") })
    }

    @Test
    fun validatorRejectsDuplicateIds() {
        val dup = listOf(remoteProvider("same"), remoteProvider("same"))
        assertTrue(AiCatalogValidator.validateRemote(dup).any { it.contains("duplicate") })
    }

    @Test
    fun mergeOverridesInPlaceAndAppendsNew() {
        val bundled = listOf(remoteProvider("bundled-a"), remoteProvider("bundled-b"))
        val remote = listOf(
            remoteProvider("bundled-a", defaultModel = "overridden-model"),
            remoteProvider("new-c"),
        )
        val merged = AiCatalogMerge.merge(bundled, remote)
        assertEquals(3, merged.size)
        assertEquals("overridden-model", merged.first { it.id == "bundled-a" }.defaultModel)
        assertTrue(merged.any { it.id == "new-c" })
    }

    @Test
    fun mergeNeverRemovesBundled() {
        val bundled = listOf(remoteProvider("a"), remoteProvider("b"))
        val remote = listOf(remoteProvider("x"))
        val merged = AiCatalogMerge.merge(bundled, remote)
        assertEquals(setOf("a", "b", "x"), merged.map { it.id }.toSet())
    }

    @Test
    fun sha256IsStableAndHex() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun catalogOverlayDoesNotReduceBundled() {
        AiProviderCatalog.applyRemote(listOf(remoteProvider("remote-only")))
        try {
            assertTrue(AiProviderCatalog.current().size > AiProviderCatalog.all.size)
            assertNull(AiProviderCatalog.byId("nonexistent"))
        } finally {
            AiProviderCatalog.applyRemote(emptyList())
        }
    }
}
