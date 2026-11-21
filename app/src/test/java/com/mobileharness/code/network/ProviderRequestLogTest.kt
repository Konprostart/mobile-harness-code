package com.mobileharness.code.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestLogTest {

    @Test
    fun `redacts api keys and bearer tokens`() {
        val out = ProviderRequestLog.redact("auth Bearer abc.def.ghi and api_key=1234567890")
        assertFalse(out.contains("abc.def.ghi"))
        assertFalse(out.contains("1234567890"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun `bounds entries`() {
        val log = ProviderRequestLog(maxEntries = 2)
        repeat(5) { log.record("openai", "gpt", ProviderRequestLog.Kind.SUCCESS) }
        assertEquals(2, log.snapshot().size)
    }

    @Test
    fun `json round trip`() {
        val log = ProviderRequestLog()
        log.record("openai", "gpt-4o", ProviderRequestLog.Kind.AUTH_FAILURE, statusCode = 401, latencyMillis = 120L, detail = "sk-secretkey123")
        val json = log.toJson()
        assertFalse(json.contains("sk-secretkey123"))
        val restored = ProviderRequestLog()
        restored.load(json)
        assertEquals(1, restored.snapshot().size)
        assertEquals(401, restored.snapshot().first().statusCode)
        assertEquals(ProviderRequestLog.Kind.AUTH_FAILURE, restored.snapshot().first().kind)
    }

    @Test
    fun `persist callback fires on record`() {
        var persisted = ""
        val log = ProviderRequestLog(persist = { persisted = it })
        log.record("anthropic", "claude", ProviderRequestLog.Kind.SUCCESS)
        assertTrue(persisted.contains("anthropic"))
    }
}
