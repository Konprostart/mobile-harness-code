package com.mobileharness.code.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderConfigStoreTest {
    @Test
    fun codecRoundTripsConfigsAndDefaults() {
        val set = ProviderConfigSet(
            configs = listOf(
                SavedProviderConfig(name = "chat", model = "gpt-4o", dshApi = "openai-completions"),
                SavedProviderConfig(name = "reasoning", model = "o1"),
            ),
            defaultModels = mapOf(AiToolTarget.DEEPSEEK to "gpt-4o", AiToolTarget.SIDEKICK to "gpt-4o-mini"),
        )
        val decoded = ProviderConfigCodec.fromJson(ProviderConfigCodec.toJson(set))
        assertEquals(2, decoded.configs.size)
        assertEquals("gpt-4o", decoded.defaultModels[AiToolTarget.DEEPSEEK])
        assertEquals("gpt-4o-mini", decoded.defaultModels[AiToolTarget.SIDEKICK])
    }

    @Test
    fun malformedJsonFailsClosed() {
        val decoded = ProviderConfigCodec.fromJson("garbage")
        assertEquals(0, decoded.configs.size)
        assertEquals(0, decoded.defaultModels.size)
    }

    @Test
    fun storeAddsDeletesAndSetsDefaults() {
        val dir = File.createTempFile("configs", "").let { it.delete(); it.mkdirs(); it }
        try {
            val store = ProviderConfigStore(dir)
            val configs = store.addConfig("p", SavedProviderConfig(name = "chat", model = "gpt-4o"))
            assertEquals(1, configs.size)
            store.setDefaultModel("p", AiToolTarget.CLAUDE_CODE, "gpt-4o")
            assertEquals("gpt-4o", store.defaultModel("p", AiToolTarget.CLAUDE_CODE))

            val afterDelete = store.deleteConfig("p", configs.first().id)
            assertEquals(0, afterDelete.size)
            // Default model persists independently of config deletion.
            assertEquals("gpt-4o", store.defaultModel("p", AiToolTarget.CLAUDE_CODE))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blankDefaultModelIsRemoved() {
        val dir = File.createTempFile("configs", "").let { it.delete(); it.mkdirs(); it }
        try {
            val store = ProviderConfigStore(dir)
            store.setDefaultModel("p", AiToolTarget.DEEPSEEK, "m")
            store.setDefaultModel("p", AiToolTarget.DEEPSEEK, "")
            assertNull(store.defaultModel("p", AiToolTarget.DEEPSEEK))
        } finally {
            dir.deleteRecursively()
        }
    }
}
