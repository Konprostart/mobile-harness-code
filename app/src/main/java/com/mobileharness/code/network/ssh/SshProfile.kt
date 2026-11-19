package com.mobileharness.code.network.ssh

import org.json.JSONArray
import org.json.JSONObject

/**
 * How the user authenticates to a remote SSH server.
 *
 *  - [PASSWORD]: password authentication.
 *  - [PRIVATE_KEY]: public-key authentication using an OpenSSH Ed25519/RSA private key,
 *    with an optional passphrase.
 */
enum class SshAuthType { PASSWORD, PRIVATE_KEY }

/**
 * A single saved SSH server. Secrets (password, private key, passphrase and the pinned
 * host-key fingerprint) are deliberately NOT part of this model — they live in
 * [SshSecretStore], keyed by [id], so a profile never carries credentials in memory or
 * through the settings UI list.
 */
data class SshProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    /** Whether the private key is encrypted and needs a passphrase at auth time. */
    val passphraseRequired: Boolean = false,
    /** Local port forwarding (SSH tunnel) configuration. */
    val tunnelEnabled: Boolean = false,
    val tunnelLocalPort: Int = 11434,
    val tunnelRemoteHost: String = "127.0.0.1",
    val tunnelRemotePort: Int = 11434,
) {
    /** Human-readable problems that block a connection; empty means the profile is well-formed. */
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Server name is required")
        if (host.isBlank()) add("Host is required")
        if (port !in 1..65535) add("Port must be 1–65535")
        if (username.isBlank()) add("Username is required")
        if (tunnelEnabled) {
            if (tunnelLocalPort !in 1..65535) add("Local port must be 1–65535")
            if (tunnelRemoteHost.isBlank()) add("Remote host is required for the tunnel")
            if (tunnelRemotePort !in 1..65535) add("Remote port must be 1–65535")
        }
    }

    fun isValid(): Boolean = validate().isEmpty()

    /** Compact `host:port` label for lists and logs. */
    fun endpointLabel(): String = "$host:$port"
}

/**
 * Pure JSON serialization for the saved SSH servers. Extracted from AppPreferences so the
 * round-trip is unit-testable without an Android context; malformed entries and unknown enum
 * values are dropped (fail-closed) rather than throwing.
 */
object SshProfileCodec {
    fun toJson(profiles: List<SshProfile>): String {
        val arr = JSONArray()
        profiles.forEach { profile ->
            arr.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("username", profile.username)
                    .put("auth_type", profile.authType.name)
                    .put("passphrase_required", profile.passphraseRequired)
                    .put("tunnel_enabled", profile.tunnelEnabled)
                    .put("tunnel_local_port", profile.tunnelLocalPort)
                    .put("tunnel_remote_host", profile.tunnelRemoteHost)
                    .put("tunnel_remote_port", profile.tunnelRemotePort),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<SshProfile> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (index in 0 until arr.length()) {
                    val obj = runCatching { arr.getJSONObject(index) }.getOrNull() ?: continue
                    val profile = parseProfile(obj) ?: continue
                    add(profile)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseProfile(obj: JSONObject): SshProfile? {
        val id = obj.optString("id").ifBlank { return null }
        return SshProfile(
            id = id,
            name = obj.optString("name"),
            host = obj.optString("host"),
            port = obj.optInt("port", 22),
            username = obj.optString("username", "root").ifBlank { "root" },
            authType = enumOrDefault(obj.optString("auth_type"), SshAuthType.PASSWORD),
            passphraseRequired = obj.optBoolean("passphrase_required", false),
            tunnelEnabled = obj.optBoolean("tunnel_enabled", false),
            tunnelLocalPort = obj.optInt("tunnel_local_port", 11434),
            tunnelRemoteHost = obj.optString("tunnel_remote_host", "127.0.0.1"),
            tunnelRemotePort = obj.optInt("tunnel_remote_port", 11434),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}
