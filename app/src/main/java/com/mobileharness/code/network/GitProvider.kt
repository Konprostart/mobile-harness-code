package com.mobileharness.code.network

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONObject

/**
 * Provider-neutral Git platform model. The rest of the app talks to [GitProviderAdapter] and these
 * types; nothing else needs to know whether a repository comes from GitHub, Gitea, OneDev or a
 * future self-hosted server. GitHub keeps its existing, proven `gh` CLI integration untouched and is
 * intentionally not routed through the new self-host provider manager.
 */
enum class GitProviderId(val stableId: String) {
    GITEA("gitea"),
    ONEDEV("onedev"),
    OWN_GIT("own_git");

    companion object {
        fun fromStableId(value: String): GitProviderId? = entries.firstOrNull { it.stableId == value }
    }
}

data class GitProviderRepo(
    val providerId: GitProviderId,
    val fullName: String,
    val cloneUrl: String,
    val sshUrl: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
    val webUrl: String,
)

data class GitProviderAccount(
    val login: String,
    val name: String,
    val avatarUrl: String,
)

enum class GitProviderAuthStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Non-secret connection settings. The access token is stored only in
 * [com.mobileharness.code.data.ApiKeyVault], never here and never in logs.
 */
data class GitProviderConfig(
    val host: String = "",
    val login: String = "",
) {
    val apiBase: String get() = host.trim().trimEnd('/')
}

/**
 * Authentication the adapter supports. Phase 1 ships PAT for Gitea/OneDev; the sealed type leaves
 * room for OAuth device-flow later without touching the core Git system.
 */
sealed interface GitProviderAuthMethod {
    /** PAT: [apiHeader] turns a token into the REST `Authorization` header value (scheme included). */
    data class PersonalAccessToken(val apiHeader: (String) -> String) : GitProviderAuthMethod
    data class OAuthDeviceFlow(val deviceCodeEndpoint: String, val tokenEndpoint: String) : GitProviderAuthMethod
}

interface GitProviderAdapter {
    val id: GitProviderId
    val displayName: String
    /** false only for the future own-server adapter, which is a stub today. */
    val available: Boolean
    /** The authentication method(s) this provider offers; PAT today, OAuth reserved. */
    val authMethod: GitProviderAuthMethod
    /** Full `Authorization` header value (scheme included) for git-over-HTTPS clone. */
    fun gitAuthorizationHeader(config: GitProviderConfig, token: String): String
    fun account(config: GitProviderConfig, token: String): GitProviderAccount
    fun repositories(config: GitProviderConfig, token: String): List<GitProviderRepo>
    fun createRepository(
        config: GitProviderConfig,
        token: String,
        name: String,
        isPrivate: Boolean,
        description: String,
    ): GitProviderRepo
}

/** `Basic` auth for git-over-HTTPS, shared by Gitea and OneDev. The token never enters a URL or log. */
fun basicAuthHeader(username: String, token: String): String =
    "Basic " + Base64.getEncoder().encodeToString(
        "${username.ifBlank { "git" }}:$token".toByteArray(Charsets.UTF_8),
    )

/**
 * Injects an `Authorization` header into git-over-HTTPS via `GIT_CONFIG_*` environment variables so
 * the access token never appears on the command line (visible in `ps`) or in a persisted git config.
 */
object GitAuthEnvironment {
    fun forExtraHeader(authHeader: String): Map<String, String> = mapOf(
        "GIT_CONFIG_COUNT" to "1",
        "GIT_CONFIG_KEY_0" to "http.extraHeader",
        "GIT_CONFIG_VALUE_0" to "Authorization: $authHeader",
    )
}

/**
 * Minimal REST helper shared by the adapters. Mirrors the existing `HttpURLConnection` + `org.json`
 * pattern used by [GitHubClient] and [ReleaseGitHubUploader] so no new HTTP dependency is introduced.
 * Throws with a user-facing message on any non-2xx status.
 */
internal object GitProviderHttp {
    fun request(method: String, url: String, apiHeader: String, body: JSONObject? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", apiHeader)
            setRequestProperty("User-Agent", "MobileHarnessCode-Android")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            error(message?.takeIf(String::isNotBlank) ?: "HTTP $status")
        }
        return text
    }
}
