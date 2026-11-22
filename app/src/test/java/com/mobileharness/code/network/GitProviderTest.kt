package com.mobileharness.code.network

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitProviderTest {
    @Test
    fun `manager registers gitea onedev and own server adapters`() {
        val manager = GitProviderManager()
        assertEquals(setOf(GitProviderId.GITEA, GitProviderId.ONEDEV, GitProviderId.OWN_GIT), manager.all().map { it.id }.toSet())
        assertEquals(GitProviderId.GITEA, manager.adapterFor(GitProviderId.GITEA).id)
        assertTrue(manager.adapterFor(GitProviderId.GITEA).available)
        assertTrue(manager.adapterFor(GitProviderId.ONEDEV).available)
        assertFalse(manager.adapterFor(GitProviderId.OWN_GIT).available)
        assertEquals(listOf(GitProviderId.GITEA, GitProviderId.ONEDEV), manager.available().map { it.id })
    }

    @Test
    fun `stable id round trips`() {
        assertEquals(GitProviderId.GITEA, GitProviderId.fromStableId("gitea"))
        assertEquals(GitProviderId.ONEDEV, GitProviderId.fromStableId("onedev"))
        assertEquals(GitProviderId.OWN_GIT, GitProviderId.fromStableId("own_git"))
        assertEquals(null, GitProviderId.fromStableId("github"))
    }

    @Test
    fun `basic auth header encodes username and token`() {
        val header = basicAuthHeader("alice", "secret-token")
        val expected = Base64.getEncoder().encodeToString("alice:secret-token".toByteArray())
        assertEquals("Basic $expected", header)
    }

    @Test
    fun `basic auth header defaults blank username to git`() {
        val header = basicAuthHeader("", "tok")
        val expected = Base64.getEncoder().encodeToString("git:tok".toByteArray())
        assertEquals("Basic $expected", header)
    }

    @Test
    fun `git auth environment injects the header without the token on the command line`() {
        val env = GitAuthEnvironment.forExtraHeader("Basic abc123")
        assertEquals("1", env["GIT_CONFIG_COUNT"])
        assertEquals("http.extraHeader", env["GIT_CONFIG_KEY_0"])
        assertEquals("Authorization: Basic abc123", env["GIT_CONFIG_VALUE_0"])
        // The token must never appear in the key names or the value beyond the header itself.
        assertTrue(env.keys.none { it.contains("token") })
    }

    @Test
    fun `gitea parse account`() {
        val account = GiteaAdapter.parseAccount(
            JSONObject().put("login", "alice").put("full_name", "Alice Example").put("avatar_url", "https://x/a.png"),
        )
        assertEquals("alice", account.login)
        assertEquals("Alice Example", account.name)
        assertEquals("https://x/a.png", account.avatarUrl)
    }

    @Test
    fun `gitea parse repository uses full_name and clone url`() {
        val repo = GiteaAdapter.parseRepository(
            JSONObject()
                .put("full_name", "acme/widget")
                .put("name", "widget")
                .put("clone_url", "https://gitea.example.com/acme/widget.git")
                .put("ssh_url", "git@gitea.example.com:acme/widget.git")
                .put("private", true)
                .put("default_branch", "main")
                .put("description", "A widget")
                .put("updated_at", "2026-01-02T00:00:00Z")
                .put("html_url", "https://gitea.example.com/acme/widget"),
        )
        assertEquals(GitProviderId.GITEA, repo.providerId)
        assertEquals("acme/widget", repo.fullName)
        assertEquals("https://gitea.example.com/acme/widget.git", repo.cloneUrl)
        assertTrue(repo.isPrivate)
        assertEquals("main", repo.defaultBranch)
    }

    @Test
    fun `gitea parse repository falls back to owner object when full_name missing`() {
        val repo = GiteaAdapter.parseRepository(
            JSONObject()
                .put("name", "widget")
                .put("owner", JSONObject().put("login", "acme"))
                .put("clone_url", "https://gitea.example.com/acme/widget.git"),
        )
        assertEquals("acme/widget", repo.fullName)
    }

    @Test
    fun `onedev parse account`() {
        val account = OneDevAdapter.parseAccount(
            JSONObject().put("name", "alice").put("fullName", "Alice Example"),
        )
        assertEquals("alice", account.login)
        assertEquals("Alice Example", account.name)
    }

    @Test
    fun `onedev build repository constructs clone and ssh urls`() {
        val config = GitProviderConfig(host = "https://onedev.example.com", login = "alice")
        val repo = OneDevAdapter.buildRepository(config, "team/proj", "widget", "A widget")
        assertEquals("team/proj/widget", repo.fullName)
        assertEquals("https://onedev.example.com/team/proj/widget.git", repo.cloneUrl)
        assertEquals("ssh://alice@onedev.example.com/team/proj/widget.git", repo.sshUrl)
        assertEquals("https://onedev.example.com/team/proj/widget", repo.webUrl)
    }

    @Test
    fun `onedev parse repository reads project path and name`() {
        val config = GitProviderConfig(host = "https://onedev.example.com", login = "alice")
        val repo = OneDevAdapter.parseRepository(
            JSONObject().put("projectPath", "/team/proj").put("name", "widget").put("description", "D"),
            config,
        )
        assertEquals("team/proj/widget", repo.fullName)
        assertEquals("https://onedev.example.com/team/proj/widget.git", repo.cloneUrl)
    }

    @Test
    fun `own server adapter is a stub that throws`() {
        val adapter = OwnGitServerAdapter()
        assertFalse(adapter.available)
        val thrown = runCatching { adapter.repositories(GitProviderConfig(), "tok") }.exceptionOrNull()
        assertTrue(thrown is UnsupportedOperationException)
    }
}
