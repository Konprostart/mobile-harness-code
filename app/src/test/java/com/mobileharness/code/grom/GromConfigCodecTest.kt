package com.mobileharness.code.grom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GromConfigCodecTest {

    @Test
    fun defaultsAreOffWithLoopbackDataPlane() {
        val config = GromConfigCodec.fromJson("")
        assertFalse(config.enabled)
        assertEquals("http://127.0.0.1:47002", config.baseUrl)
        assertEquals(GromConfig.AUTH_PASSWORD, config.authMethod)
    }

    @Test
    fun roundTripsAllFields() {
        val original = GromConfig(
            enabled = true,
            baseUrl = "http://192.168.1.20:47002",
            authMethod = GromConfig.AUTH_KEY,
            keyId = "0123456789abcdef",
            publicKey = "base64public",
        )
        val decoded = GromConfigCodec.fromJson(GromConfigCodec.toJson(original))
        assertEquals(original, decoded)
        assertTrue(decoded.enabled)
    }

    @Test
    fun ignoresUnknownFieldsWithoutLosingKnownOnes() {
        val json = """{"enabled":true,"baseUrl":"http://127.0.0.1:47002","unknown":"x"}"""
        val config = GromConfigCodec.fromJson(json)
        assertTrue(config.enabled)
        assertEquals("http://127.0.0.1:47002", config.baseUrl)
    }
}
