package com.mobileharness.code.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * OneDev REST adapter. Auth is an access token sent as `Authorization: Bearer <token>`; git-over-HTTPS
 * uses HTTP Basic auth with the token as password. Endpoints (OneDev REST v1):
 *   - account:           GET  /api/users/me
 *   - repositories:      GET  /api/repositories?offset=0&count=500
 *   - createRepository:  POST /api/projects/{projectId}/repositories
 *
 * OneDev groups repositories under projects, so create first reuses the user's first accessible
 * project. Clone URLs are constructed from the project path and repository name.
 */
class OneDevAdapter : GitProviderAdapter {
    override val id: GitProviderId = GitProviderId.ONEDEV
    override val displayName: String = "OneDev"
    override val available: Boolean = true
    override val authMethod: GitProviderAuthMethod =
        GitProviderAuthMethod.PersonalAccessToken(apiHeader = { "Bearer $it" })

    override fun gitAuthorizationHeader(config: GitProviderConfig, token: String): String =
        basicAuthHeader(config.login, token)

    override fun account(config: GitProviderConfig, token: String): GitProviderAccount =
        parseAccount(
            JSONObject(
                GitProviderHttp.request(
                    "GET",
                    "${config.apiBase}/api/users/me",
                    apiHeader(token),
                ),
            ),
        )

    override fun repositories(config: GitProviderConfig, token: String): List<GitProviderRepo> {
        val array = JSONArray(
            GitProviderHttp.request(
                "GET",
                "${config.apiBase}/api/repositories?offset=0&count=500",
                apiHeader(token),
            ),
        )
        val result = LinkedHashMap<String, GitProviderRepo>()
        for (index in 0 until array.length()) {
            val repo = parseRepository(array.getJSONObject(index), config)
            result[repo.fullName] = repo
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
        val projects = JSONArray(
            GitProviderHttp.request(
                "GET",
                "${config.apiBase}/api/projects?offset=0&count=1",
                apiHeader(token),
            ),
        )
        val project = projects.optJSONObject(0)
            ?: error("No OneDev project is available to hold the repository")
        val projectId = project.optLong("id")
        val projectPath = project.optString("path").trim('/')
        val body = JSONObject()
            .put("name", name)
            .put("description", description)
        GitProviderHttp.request(
            "POST",
            "${config.apiBase}/api/projects/$projectId/repositories",
            apiHeader(token),
            body,
        )
        return buildRepository(config, projectPath, name, description)
    }

    private fun apiHeader(token: String): String =
        (authMethod as GitProviderAuthMethod.PersonalAccessToken).apiHeader(token)

    companion object {
        internal fun parseAccount(json: JSONObject): GitProviderAccount = GitProviderAccount(
            login = json.optString("name"),
            name = json.optString("fullName").ifBlank { json.optString("name") },
            avatarUrl = "",
        )

        internal fun parseRepository(json: JSONObject, config: GitProviderConfig): GitProviderRepo {
            val projectPath = json.optString("projectPath").trim('/')
            val name = json.optString("name")
            return buildRepository(config, projectPath, name, json.optString("description"))
        }

        internal fun buildRepository(
            config: GitProviderConfig,
            projectPath: String,
            name: String,
            description: String,
        ): GitProviderRepo {
            val cleanPath = projectPath.trim('/')
            val fullName = listOf(cleanPath, name).filter(String::isNotBlank).joinToString("/")
            val base = config.apiBase
            val host = base.substringAfter("://").substringBefore('/')
            return GitProviderRepo(
                providerId = GitProviderId.ONEDEV,
                fullName = fullName,
                cloneUrl = "$base/$cleanPath/$name.git",
                sshUrl = "ssh://${config.login.ifBlank { "git" }}@$host/$cleanPath/$name.git",
                isPrivate = false,
                defaultBranch = "main",
                description = description,
                updatedAt = "",
                webUrl = "$base/$cleanPath/$name",
            )
        }
    }
}
