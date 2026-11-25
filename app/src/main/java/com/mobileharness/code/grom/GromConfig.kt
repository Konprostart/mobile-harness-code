package com.mobileharness.code.grom

import org.json.JSONObject

/**
 * Persisted, non-secret configuration for the optional GROM connection.
 *
 * The connection is OFF by default and the address defaults to Grom's loopback
 * data plane (S6-R003). Secrets (the public-API password and the Ed25519 private
 * key) are deliberately NOT stored here: they live in the host's own encrypted
 * secret store (`ApiKeyVault`), so a config dump never leaks a credential.
 */
data class GromConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    /** [AUTH_PASSWORD] (a public-API password → short-lived session) or [AUTH_KEY] (Ed25519). */
    val authMethod: String = AUTH_PASSWORD,
    /** 16-hex Ed25519 key id, non-empty once a client key has been generated. */
    val keyId: String = "",
    /** Base64 public key for pasting into Grom's "Trust a key" screen. */
    val publicKey: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL: String = "http://127.0.0.1:47002"
        const val AUTH_PASSWORD: String = "password"
        const val AUTH_KEY: String = "key"
    }
}

object GromConfigCodec {
    fun toJson(config: GromConfig): String = JSONObject()
        .put("enabled", config.enabled)
        .put("baseUrl", config.baseUrl)
        .put("authMethod", config.authMethod)
        .put("keyId", config.keyId)
        .put("publicKey", config.publicKey)
        .toString()

    fun fromJson(raw: String): GromConfig = runCatching {
        val o = JSONObject(raw.ifBlank { "{}" })
        GromConfig(
            enabled = o.optBoolean("enabled", false),
            baseUrl = o.optString("baseUrl", GromConfig.DEFAULT_BASE_URL),
            authMethod = o.optString("authMethod", GromConfig.AUTH_PASSWORD),
            keyId = o.optString("keyId", ""),
            publicKey = o.optString("publicKey", ""),
        )
    }.getOrDefault(GromConfig())
}

/** Non-secret config persistence, decoupled from Android for JVM testing. */
interface GromConfigStore {
    fun load(): GromConfig
    fun save(config: GromConfig)
}

/** Encrypted secret persistence, decoupled from Android for JVM testing. */
interface GromSecretStore {
    fun put(id: String, secret: String)
    fun get(id: String): String?
    fun remove(id: String)
}
