package com.mobileharness.code.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the existing GitHub repo-listing parser (the `gh api --paginate --slurp`
 * JSON shape). Locks the exact behaviour the GitHub integration depends on so future changes to the
 * Git provider layer cannot silently alter GitHub's output.
 */
class GitHubRepositoryParserTest {
    @Test
    fun `parses slurped pages and deduplicates by full name`() {
        val page = JSONArray().put(
            JSONObject()
                .put("full_name", "acme/widget")
                .put("clone_url", "https://github.com/acme/widget.git")
                .put("private", false)
                .put("default_branch", "main")
                .put("description", "A widget")
                .put("updated_at", "2026-01-02T00:00:00Z"),
        ).put(
            JSONObject()
                .put("full_name", "acme/secret")
                .put("clone_url", "https://github.com/acme/secret.git")
                .put("private", true)
                .put("description", "Secret repo"),
        )
        val slurped = JSONArray().put(page).toString()

        val repos = GitHubRepositoryParser.parseSlurped(slurped)
        assertEquals(2, repos.size)
        assertEquals("acme/widget", repos[0].fullName)
        assertEquals("main", repos[0].defaultBranch)
        assertTrue(repos[1].private)
        assertEquals("main", repos[1].defaultBranch)
    }

    @Test
    fun `falls back clone url to github host and branch to main`() {
        val page = JSONArray().put(
            JSONObject().put("full_name", "acme/widget").put("private", false),
        )
        val repos = GitHubRepositoryParser.parseSlurped(JSONArray().put(page).toString())
        assertEquals(1, repos.size)
        assertEquals("https://github.com/acme/widget.git", repos[0].cloneUrl)
        assertEquals("main", repos[0].defaultBranch)
    }

    @Test
    fun `deduplicates repeated full names across pages`() {
        val first = JSONArray().put(JSONObject().put("full_name", "acme/widget"))
        val second = JSONArray().put(JSONObject().put("full_name", "acme/widget"))
        val repos = GitHubRepositoryParser.parseSlurped(JSONArray().put(first).put(second).toString())
        assertEquals(1, repos.size)
    }

    @Test
    fun `skips entries without a full name`() {
        val page = JSONArray().put(JSONObject().put("name", "no-full-name"))
        assertEquals(0, GitHubRepositoryParser.parseSlurped(JSONArray().put(page).toString()).size)
    }
}
