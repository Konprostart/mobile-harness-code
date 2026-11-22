package com.mobileharness.code.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gitea REST adapter (Gitea API v1). Auth is a Personal Access Token sent as `Authorization: token <pat>`;
 * git-over-HTTPS uses HTTP Basic auth with the token as password. Endpoints:
 *   - account:           GET  /api/v1/user
 *   - repositories:      GET  /api/v1/user/repos?limit=50&page=N
 *   - createRepository:  POST /api/v1/user/repos
 */
class GiteaAdapter : GitProviderAdapter {
    override val id: GitProviderId = GitProviderId.GITEA
    override val displayName: String = "Gitea"
    override val available: Boolean = true
    override val authMethod: GitProviderAuthMethod =
        GitProviderAuthMethod.PersonalAccessToken(apiHeader = { "token $it" })

    override fun gitAuthorizationHeader(config: GitProviderConfig, token: String): String =
        basicAuthHeader(config.login, token)

    override fun account(config: GitProviderConfig, token: String): GitProviderAccount =
        parseAccount(
            JSONObject(
                GitProviderHttp.request(
                    "GET",
                    "${config.apiBase}/api/v1/user",
                    (authMethod as GitProviderAuthMethod.PersonalAccessToken).apiHeader(token),
                ),
            ),
        )

    override fun repositories(config: GitProviderConfig, token: String): List<GitProviderRepo> {
        val header = (authMethod as GitProviderAuthMethod.PersonalAccessToken).apiHeader(token)
        val result = LinkedHashMap<String, GitProviderRepo>()
        var page = 1
        while (page <= 10) {
            val array = JSONArray(
                GitProviderHttp.request(
                    "GET",
                    "${config.apiBase}/api/v1/user/repos?limit=50&page=$page",
                    header,
                ),
            )
            for (index in 0 until array.length()) {
                val repo = parseRepository(array.getJSONObject(index))
                result[repo.fullName] = repo
            }
            if (array.length() < 50) break
            page++
        }
        return result.values.toList()
    }

    override fun createRepository(
        config: GitProviderConfig,
        token: String,
        name: String,
        isPrivate: Boolean,
        description: String,
    ): GitProviderRepo {
        val header = (authMethod as GitProviderAuthMethod.PersonalAccessToken).apiHeader(token)
        val body = JSONObject()
            .put("name", name)
            .put("private", isPrivate)
            .put("description", description)
            .put("auto_init", true)
        return parseRepository(
            JSONObject(
                GitProviderHttp.request(
                    "POST",
                    "${config.apiBase}/api/v1/user/repos",
                    header,
                    body,
                ),
            ),
        )
    }

    companion object {
        internal fun parseAccount(json: JSONObject): GitProviderAccount = GitProviderAccount(
            login = json.optString("login"),
            name = json.optString("full_name").ifBlank { json.optString("login") },
            avatarUrl = json.optString("avatar_url"),
        )

        internal fun parseRepository(json: JSONObject): GitProviderRepo {
            val name = json.optString("name")
            val fullName = json.optString("full_name").ifBlank {
                val ownerLogin = json.optJSONObject("owner")?.optString("login").orEmpty()
                listOf(ownerLogin, name).filter(String::isNotBlank).joinToString("/")
            }
            return GitProviderRepo(
                providerId = GitProviderId.GITEA,
                fullName = fullName,
                cloneUrl = json.optString("clone_url"),
                sshUrl = json.optString("ssh_url"),
                isPrivate = json.optBoolean("private"),
                defaultBranch = json.optString("default_branch", "main"),
                description = json.optString("description"),
                updatedAt = json.optString("updated_at"),
                webUrl = json.optString("html_url"),
            )
        }
    }
}
