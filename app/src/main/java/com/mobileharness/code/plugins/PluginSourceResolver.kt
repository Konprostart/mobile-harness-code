package com.mobileharness.code.plugins

import com.mobileharness.code.network.LocalNetwork
import java.net.URI

/**
 * Resolves a user/agent-supplied plugin source string into a normalized [PluginSourceSpec] and
 * validates that its download target is an HTTPS host (never loopback/private/link-local) — an SSRF
 * guard mirroring the MCP and research policies. Unknown source strings are rejected (fail-closed).
 */
object PluginSourceResolver {

    /** Hosts allowed for arbitrary ZIP/plugin downloads. The maintainer can extend this list. */
    val allowedHosts: Set<String> = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "codeload.github.com",
        "registry.npmjs.org",
        "objects.githubusercontent.com",
    )

    fun resolve(raw: String): PluginSourceSpec? {
        val input = raw.trim()
        if (input.isEmpty() || input.length > 2_048) return null

        // npm: package name or `package@version`.
        if (input.startsWith("npm:")) {
            val spec = input.removePrefix("npm:").trim()
            return if (isValidNpmName(spec)) PluginSourceSpec(PluginSource.NPM, spec, isNpmPackage = true) else null
        }

        if (input.startsWith("zip:")) {
            val url = input.removePrefix("zip:").trim()
            return if (isAllowedDownloadUrl(url)) PluginSourceSpec(PluginSource.ZIP, url) else null
        }

        if (input.startsWith("marketplace:")) {
            val id = input.removePrefix("marketplace:").trim()
            return if (id.isNotBlank() && id.length <= 256 && !id.any { it.code < 0x20 }) {
                PluginSourceSpec(PluginSource.MARKETPLACE, id)
            } else {
                null
            }
        }

        // Bare HTTPS URL → GitHub-ish source.
        if (input.startsWith("https://") || input.startsWith("http://")) {
            return if (isAllowedDownloadUrl(input)) {
                val source = if (input.contains("github.com")) PluginSource.GITHUB else PluginSource.ZIP
                PluginSourceSpec(source, input)
            } else {
                null
            }
        }

        // `owner/repo` shorthand → GitHub.
        if (Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(input)) {
            return PluginSourceSpec(PluginSource.GITHUB, "https://github.com/$input")
        }

        return null
    }

    fun isAllowedDownloadUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host ?: return false
        if (scheme != "https") return false
        if (host.isBlank() || LocalNetwork.isLocalHost(host)) return false
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun isValidNpmName(spec: String): Boolean {
        val name = npmName(spec) ?: return false
        if (name.length > 214) return false
        if (name.any { it.code < 0x20 }) return false
        return Regex("^(@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+$").matches(name)
    }

    /** Strips an optional trailing `@version` while keeping a leading `@scope/`. */
    private fun npmName(spec: String): String? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        return if (s.startsWith("@")) {
            val slash = s.indexOf('/')
            if (slash < 0) return null
            val scope = s.substring(0, slash)
            val rest = s.substring(slash + 1)
            "$scope/${rest.substringBefore('@')}"
        } else {
            s.substringBefore('@')
        }
    }
}
