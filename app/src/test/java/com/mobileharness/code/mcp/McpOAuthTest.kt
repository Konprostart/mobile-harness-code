package com.mobileharness.code.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthTest {

    @Test
    fun verifierIsBase64UrlWithoutPadding() {
        val verifier = McpOAuth.createVerifier()
        assertTrue(verifier.length in 43..128)
        assertFalse(verifier.contains('+') || verifier.contains('/') || verifier.contains('='))
    }

    @Test
    fun codeChallengeIsDeterministic() {
        val verifier = "test-verifier"
        assertEquals(McpOAuth.codeChallenge(verifier), McpOAuth.codeChallenge(verifier))
        assertNotEquals(verifier, McpOAuth.codeChallenge(verifier))
    }

    @Test
    fun authorizationUrlContainsPkceParameters() {
        val ctx = McpOAuth.Context(
            resource = "https://mcp.example.com",
            authorizationEndpoint = "https://auth.example.com/authorize",
            tokenEndpoint = "https://auth.example.com/token",
            clientId = "client-1",
            scopes = "read write",
        )
        val url = McpOAuth.authorizationUrl(ctx, "state123", "challenge456")
        assertTrue(url.startsWith("https://auth.example.com/authorize?"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=challenge456"))
        assertTrue(url.contains("state=state123"))
        assertTrue(url.contains("client_id=client-1"))
        assertTrue(url.contains("resource="))
    }

    @Test
    fun authorizationUrlHandlesExistingQuery() {
        val ctx = McpOAuth.Context("r", "https://a.example.com/x?y=1", "https://t")
        val url = McpOAuth.authorizationUrl(ctx, "s", "c")
        assertTrue(url.contains("x?y=1&response_type=code"))
    }

    @Test
    fun hmacSignAndVerify() {
        val sig = HmacIntegrity.sign("payload", "secret")
        assertTrue(HmacIntegrity.verify("payload", sig, "secret"))
        assertFalse(HmacIntegrity.verify("payload-other", sig, "secret"))
        assertFalse(HmacIntegrity.verify("payload", sig, "wrong-secret"))
    }

    @Test
    fun hmacSignatureIsStableHex() {
        val sig = HmacIntegrity.sign("payload", "secret")
        assertEquals(sig, HmacIntegrity.sign("payload", "secret"))
        assertEquals(64, sig.length)
    }
}
