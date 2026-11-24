package com.mobileharness.code.mcp

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth 2.1 with PKCE helpers for MCP servers, ported and adapted from AndroidHarness 4.0
 * (https://github.com/Sanuu7/AndroidHarness), MIT License.
 *
 * This object contains only the pure, testable pieces: PKCE verifier/challenge generation and the
 * authorization-URL builder (RFC 7636 + RFC 8707 resource indicators). The network round-trips
 * (protected-resource metadata discovery, dynamic client registration, token exchange) are performed
 * by the existing MCP HTTP layer, which already applies the McpPolicy URL/HTTPS guards.
 */
object McpOAuth {

    /** Redirect target for the browser round-trip (a custom scheme registered by the app). */
    const val REDIRECT_URI = "mobileharness://mcp/oauth"

    /** Discovered OAuth plumbing for one MCP server. */
    data class Context(
        /** Canonical resource identifier sent as the RFC 8707 `resource` parameter. */
        val resource: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val clientId: String? = null,
        val clientSecret: String? = null,
        val scopes: String? = null,
    )

    fun createVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return base64UrlNoPadding(bytes)
    }

    /** S256 code challenge for a PKCE verifier. */
    fun codeChallenge(verifier: String): String =
        base64UrlNoPadding(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    fun createState(): String = createVerifier()

    fun authorizationUrl(ctx: Context, state: String, codeChallenge: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to (ctx.clientId ?: ""),
            "redirect_uri" to REDIRECT_URI,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "resource" to ctx.resource,
        )
        ctx.scopes?.let { params["scope"] = it }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val sep = if (ctx.authorizationEndpoint.contains('?')) '&' else '?'
        return ctx.authorizationEndpoint + sep + query
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * HMAC-SHA256 integrity helpers used to sign and verify bundled catalogs and other integrity-
 * protected payloads. Comparison is constant-time to avoid timing side channels.
 */
object HmacIntegrity {
    fun sign(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun verify(payload: String, signature: String, secret: String): Boolean {
        val expected = sign(payload, secret)
        return constantTimeEquals(expected.lowercase(), signature.lowercase())
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
