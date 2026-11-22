package com.mobileharness.code.network

/**
 * Central registry for the self-host Git providers. The rest of the app picks an adapter by
 * [GitProviderId]; adding a future provider is one new class registered here — no other code changes.
 */
class GitProviderManager {
    private val adapters: Map<GitProviderId, GitProviderAdapter> = listOf(
        GiteaAdapter(),
        OneDevAdapter(),
        OwnGitServerAdapter(),
    ).associateBy { it.id }

    fun adapterFor(id: GitProviderId): GitProviderAdapter = adapters.getValue(id)

    fun all(): List<GitProviderAdapter> = adapters.values.toList()

    fun available(): List<GitProviderAdapter> = all().filter { it.available }
}

/**
 * Future self-hosted server. This is a real [GitProviderAdapter] stub, not fake functionality: every
 * operation throws, and [available] is false so the UI can present it as "coming soon" instead of
 * pretending it works. When the server ships, replace the throws with real REST calls.
 */
class OwnGitServerAdapter : GitProviderAdapter {
    override val id: GitProviderId = GitProviderId.OWN_GIT
    override val displayName: String = "Own Git Server"
    override val available: Boolean = false
    override val authMethod: GitProviderAuthMethod =
        GitProviderAuthMethod.PersonalAccessToken(apiHeader = { "token $it" })

    override fun gitAuthorizationHeader(config: GitProviderConfig, token: String): String =
        basicAuthHeader(config.login, token)

    override fun account(config: GitProviderConfig, token: String): GitProviderAccount =
        unsupported()

    override fun repositories(config: GitProviderConfig, token: String): List<GitProviderRepo> =
        unsupported()

    override fun createRepository(
        config: GitProviderConfig,
        token: String,
        name: String,
        isPrivate: Boolean,
        description: String,
    ): GitProviderRepo = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("The own Git server is not available yet")
}
