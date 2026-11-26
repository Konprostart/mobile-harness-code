package com.mobileharness.code.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudStorageRegistryTest {

    @Test
    fun registerAndCreate() {
        val provider = FakeProvider("a")
        CloudStorageRegistry.register(CloudProviderSpec("a", "A", ConnectionKind.NONE)) { provider }
        val created = CloudStorageRegistry.create("a", "")
        assertSame(provider, created)
        CloudStorageRegistry.unregister("a")
    }

    @Test
    fun listReturnsSortedSpecs() {
        CloudStorageRegistry.register(CloudProviderSpec("z", "Z", ConnectionKind.NONE)) { FakeProvider("z") }
        CloudStorageRegistry.register(CloudProviderSpec("a", "A", ConnectionKind.PICK_FOLDER)) { FakeProvider("a") }
        val ids = CloudStorageRegistry.list().map { it.id }
        assertEquals(listOf("a", "z"), ids)
        CloudStorageRegistry.unregister("z")
        CloudStorageRegistry.unregister("a")
    }

    @Test
    fun unknownProviderThrows() {
        try {
            CloudStorageRegistry.create("missing", "")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("missing"))
        }
    }

    private class FakeProvider(override val id: String) : CloudStorageProvider {
        override val displayName: String = id
        override val isConnected: Boolean = false
        override fun connect() {}
        override fun disconnect() {}
        override fun list(prefix: String): List<CloudObject> = emptyList()
        override fun upload(name: String, bytes: ByteArray) {}
        override fun download(name: String): ByteArray = byteArrayOf()
        override fun delete(name: String) {}
        override fun exists(name: String): Boolean = false
    }
}
