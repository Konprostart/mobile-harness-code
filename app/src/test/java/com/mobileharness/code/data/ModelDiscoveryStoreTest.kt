package com.mobileharness.code.data

import com.mobileharness.code.network.DiscoveredModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDiscoveryStoreTest {
    @Test
    fun codecRoundTrips() {
        val cached = CachedModels(
            models = listOf(DiscoveredModel("m1", "Model One", isFree = true), DiscoveredModel("m2")),
            endpoint = "https://api.example.com/v1/models",
            checkedAtMillis = 123L,
        )
        val decoded = ModelDiscoveryCodec.fromJson(ModelDiscoveryCodec.toJson(cached))!!
        assertEquals(2, decoded.models.size)
        assertEquals("m1", decoded.models[0].id)
        assertEquals(true, decoded.models[0].isFree)
        assertEquals(123L, decoded.checkedAtMillis)
    }

    @Test
    fun malformedJsonFailsClosed() {
        assertNull(ModelDiscoveryCodec.fromJson("not json"))
        assertNull(ModelDiscoveryCodec.fromJson("""{"models":42}"""))
    }

    @Test
    fun storePersistsAndClears() {
        val dir = File.createTempFile("models", "").let { it.delete(); it.mkdirs(); it }
        try {
            val store = ModelDiscoveryStore(dir)
            assertNull(store.load("p"))
            store.save("p", listOf(DiscoveredModel("a"), DiscoveredModel("b")), "endpoint")
            val cached = store.load("p")
            assertEquals(2, cached?.models?.size)
            store.clear("p")
            assertNull(store.load("p"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
