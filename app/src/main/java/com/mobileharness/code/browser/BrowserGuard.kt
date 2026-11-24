package com.mobileharness.code.browser

import com.mobileharness.code.network.LocalNetwork
import java.net.URI

/**
 * Fail-closed URL / SSRF / download guard for the agent browser. Web content is always treated as
 * an untrusted input, so navigation is restricted to public HTTPS (plain HTTP only for loopback
 * development servers) and any attempt to reach the loopback, link-local or cloud-metadata space is
 * blocked unless the host is an explicit, user-approved local endpoint.
 */
object BrowserGuard {
    const val MAX_DOWNLOAD_BYTES = 20_000_000L
    const val MAX_URL_CHARS = 2048

    /** Content types that must never be auto-executed or treated as inline document text. */
    val BLOCKED_DOWNLOAD_CONTENT_TYPES = setOf(
        "application/x-executable", "application/x-msdownload", "application/x-sh",
        "application/vnd.android.package-archive", "application/x-dosexec", "application/java-archive",
    )

    enum class UrlDecision { ALLOWED, INVALID, BLOCKED_SCHEME, BLOCKED_SSRF, BLOCKED_USERINFO, TOO_LONG }

    fun classify(url: String): UrlDecision {
        if (url.length > MAX_URL_CHARS) return UrlDecision.TOO_LONG
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return UrlDecision.INVALID
        if (uri.userInfo != null) return UrlDecision.BLOCKED_USERINFO
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("https", "http")) return UrlDecision.BLOCKED_SCHEME
        val host = uri.host ?: return UrlDecision.INVALID
        if (scheme == "http" && !LocalNetwork.isLocalHost(host)) return UrlDecision.BLOCKED_SCHEME
        // SSRF: never allow the cloud metadata endpoint or the local loopback from a remote page.
        if (isSsrfHost(host)) return UrlDecision.BLOCKED_SSRF
        return UrlDecision.ALLOWED
    }

    fun isAllowed(url: String): Boolean = classify(url) == UrlDecision.ALLOWED

    /**
     * Blocks loopback, link-local (169.254.x.x incl. cloud metadata 169.254.169.254), and private
     * ranges unless the host is an explicitly local host name the user may browse (e.g. localhost
     * for a dev server). The browser is for public research, not for reaching internal services.
     */
    fun isSsrfHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".home.arpa")) return true
        if (h.contains(':')) return h == "::1" || h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb") || h.startsWith("fd") || h.startsWith("fc")
        val octets = h.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull()?.let { v -> v in 0..255 } == true }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }
        return false
    }

    /** Whether a download with the given length/content-type is safe to keep (never auto-run). */
    fun isDownloadAllowed(contentLength: Long, contentType: String?): Boolean {
        if (contentLength > MAX_DOWNLOAD_BYTES) return false
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return type !in BLOCKED_DOWNLOAD_CONTENT_TYPES
    }

    /** Content types that yield readable text/document content. */
    val READABLE_CONTENT_TYPES = setOf(
        "text/html", "text/plain", "application/json", "application/xml", "text/xml",
        "application/pdf", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )
}
