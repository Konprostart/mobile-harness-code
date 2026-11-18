package com.mobileharness.code.network

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ReleasePublishResult(
    val releaseId: Long? = null,
    val assetUrl: String? = null,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null
}

/**
 * Publishes a locally-signed artifact to a GitHub Release. The personal access
 * token (PAT) is passed in by the caller for the duration of one request only:
 * it is never written to disk, never stored in preferences, and never shipped in
 * the repository or CI (mirroring the project's keyless-CI rule).
 */
class ReleaseGitHubUploader {
    fun publish(
        owner: String,
        repo: String,
        tag: String,
        releaseName: String,
        body: String,
        token: String,
        artifact: File,
    ): ReleasePublishResult {
        if (token.isBlank()) return ReleasePublishResult(error = "A GitHub token is required to publish")
        if (!artifact.isFile || artifact.length() == 0L) return ReleasePublishResult(error = "Nothing to publish")

        val release = createOrGetRelease(owner, repo, tag, releaseName, body, token)
            ?: return ReleasePublishResult(error = "Could not create or find the GitHub release")
        return uploadAsset(release.uploadUrl, artifact, token)
    }

    private fun createOrGetRelease(
        owner: String,
        repo: String,
        tag: String,
        releaseName: String,
        body: String,
        token: String,
    ): CreatedRelease? {
        // Prefer an existing release so re-publishing an already-tagged build
        // replaces the asset instead of creating a duplicate.
        val existing = getJson(
            "https://api.github.com/repos/$owner/$repo/releases/tags/${URLEncoder.encode(tag, "UTF-8")}",
            token,
        )
        if (existing != null) return CreatedRelease(existing.optLong("id"), existing.optString("upload_url"))

        val created = postJson(
            "https://api.github.com/repos/$owner/$repo/releases",
            token,
            JSONObject()
                .put("tag_name", tag)
                .put("name", releaseName)
                .put("body", body)
                .put("draft", false)
                .put("prerelease", false),
        ) ?: return null
        return CreatedRelease(created.optLong("id"), created.optString("upload_url"))
    }

    private fun uploadAsset(uploadUrl: String, artifact: File, token: String): ReleasePublishResult {
        val name = URLEncoder.encode(artifact.name, "UTF-8")
        val endpoint = uploadUrl
            .replace("{?name,label}", "?name=$name")
            .replace("{?name}", "?name=$name")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Content-Type", "application/vnd.android.package-archive")
            setRequestProperty("User-Agent", "MobileHarnessCode-Android")
        }
        connection.outputStream.use { output ->
            artifact.inputStream().use { input -> input.copyTo(output) }
        }
        val status = connection.responseCode
        val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(responseText).optString("message") }.getOrNull()
            return ReleasePublishResult(error = message?.takeIf(String::isNotBlank) ?: "GitHub returned HTTP $status")
        }
        val assetUrl = runCatching { JSONObject(responseText).optString("browser_download_url") }.getOrNull()
        return ReleasePublishResult(assetUrl = assetUrl)
    }

    private data class CreatedRelease(val id: Long, val uploadUrl: String)

    private fun getJson(endpoint: String, token: String): JSONObject? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("User-Agent", "MobileHarnessCode-Android")
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun postJson(endpoint: String, token: String, body: JSONObject): JSONObject? {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "MobileHarnessCode-Android")
        }
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }
}
