package com.mobileharness.code.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderLibraryTest {
    @Test
    fun catalogIsInternallyConsistent() {
        assertTrue(AiProviderCatalog.all.size >= 40)
        assertEquals(emptyList<String>(), AiProviderCatalog.validate())
    }

    @Test
    fun everyCategoryHasProviders() {
        for (category in AiProviderCategory.entries) {
            assertTrue("category $category is empty", AiProviderCatalog.byCategory(category).isNotEmpty())
        }
    }

    @Test
    fun idsAreUniqueAndSearchable() {
        val ids = AiProviderCatalog.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)

        // Search by name fragment, id, and category label.
        assertTrue(AiProviderCatalog.search("openai").any { it.id == "openai" })
        assertTrue(AiProviderCatalog.search("groq").any { it.id == "groq" })
        assertTrue(AiProviderCatalog.search("router").isNotEmpty())
        assertTrue(AiProviderCatalog.search("local").isNotEmpty())
    }

    @Test
    fun byIdReturnsTheRightProvider() {
        assertEquals("OpenAI", AiProviderCatalog.byId("openai")?.name)
        assertEquals("Anthropic (Claude)", AiProviderCatalog.byId("anthropic")?.name)
        assertEquals("OpenRouter", AiProviderCatalog.byId("openrouter")?.name)
        assertNotNull(AiProviderCatalog.byId("ollama"))
    }

    @Test
    fun directlyUsableProvidersMapToADshWireValue() {
        val directlyUsable = AiProviderCatalog.all.filterNot { it.needsAdapter }
        for (provider in directlyUsable) {
            assertNotNull("${provider.id} should have a dsh mapping", provider.dshApi)
        }
    }

    @Test
    fun adapterRequiredProvidersAreMarked() {
        val native = AiProviderCatalog.all.filter { it.needsAdapter }
        assertTrue(native.isNotEmpty())
        for (provider in native) {
            assertEquals(AiWireProtocol.NATIVE, provider.wireProtocol)
        }
    }

    @Test
    fun knownEndpointsArePinned() {
        assertEquals("https://api.openai.com/v1", AiProviderCatalog.byId("openai")?.baseUrl)
        assertEquals("https://api.anthropic.com", AiProviderCatalog.byId("anthropic")?.baseUrl)
        assertEquals("https://api.groq.com/openai/v1", AiProviderCatalog.byId("groq")?.baseUrl)
        assertEquals("https://openrouter.ai/api/v1", AiProviderCatalog.byId("openrouter")?.baseUrl)
        assertEquals("http://localhost:11434/v1", AiProviderCatalog.byId("ollama")?.baseUrl)
        assertEquals("http://localhost:1234/v1", AiProviderCatalog.byId("lm-studio")?.baseUrl)
    }

    @Test
    fun localServersUseNoAuth() {
        val local = AiProviderCatalog.byCategory(AiProviderCategory.LOCAL)
        assertFalse(local.isEmpty())
        for (provider in local) {
            assertEquals(AiAuthType.NONE, provider.authType)
        }
    }

    @Test
    fun allBaseUrlsAreHttpsOrLocalhost() {
        val problems = AiProviderCatalog.validate().filter { it.contains("invalid base URL") }
        assertEquals(emptyList<String>(), problems)
    }
}
