package com.mobileharness.code.data

import com.mobileharness.code.model.AiCatalogCodec
import com.mobileharness.code.model.AiCatalogMerge
import com.mobileharness.code.model.AiCatalogValidator
import com.mobileharness.code.model.AiProvider
import com.mobileharness.code.model.AiProviderCatalog
import com.mobileharness.code.model.sha256Hex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The single remote endpoint for the additional AI provider catalog. It is our own repo, so the
 * app never depends on a third-party provider's API key, account or service; moving the catalog to
 * another host later is a one-constant change (the catalog is metadata, all relative to this URL).
 */
object AiCatalogDefaults {
    const val CATALOG_URL =
        "https://raw.githubusercontent.com/Konprostart/mobile-harness-ai-catalog/main/catalog.json"
    const val CATALOG_SHA_URL =
        "https://raw.githubusercontent.com/Konprostart/mobile-harness-ai-catalog/main/catalog.json.sha256"
}

/** The last accepted catalog plus its metadata, kept on device for offline use. */
data class CachedAiCatalog(
    val version: Long,
    val updatedAt: String,
    val fetchedAtMillis: Long,
    val providers: List<AiProvider>,
)

sealed interface AiCatalogRefreshResult {
    data class Updated(val version: Long, val providerCount: Int) : AiCatalogRefreshResult
    data object UpToDate : AiCatalogRefreshResult

    /** Offline, oversized, tampered or malformed remote document — the cached/bundled catalog stands. */
    data class Unavailable(val reason: String) : AiCatalogRefreshResult
}

/**
 * Fetches, verifies and caches the remote AI provider catalog. Fail-closed: any problem (no network,
 * wrong SHA-256, invalid schema, oversized document, or a non-HTTPS/redirected response) leaves the
 * bundled catalog and the last cached copy untouched. The remote document is metadata only and can
 * never read or change the user's API keys or saved configurations.
 */
class AiCatalogStore(
    private val dir: File,
    private val catalogUrl: String = AiCatalogDefaults.CATALOG_URL,
    private val shaUrl: String = AiCatalogDefaults.CATALOG_SHA_URL,
) {
    private val catalogFile = File(dir, "ai_catalog.json")
    private val metaFile = File(dir, "ai_catalog_meta.json")

    /** The catalog the app should show: last cached merge, else the bundled catalog. */
    fun mergedProviders(): List<AiProvider> = cached()?.providers ?: AiProviderCatalog.all

    fun cached(): CachedAiCatalog? {
        if (!catalogFile.exists()) return null
        val parsed = AiCatalogCodec.parse(catalogFile.readText()).getOrNull() ?: return null
        val problems = AiCatalogValidator.validateRemote(parsed.providers)
        if (problems.isNotEmpty()) return null
        val meta = runCatching { org.json.JSONObject(metaFile.readText()) }.getOrNull()
        return CachedAiCatalog(
            version = parsed.version,
            updatedAt = parsed.updatedAt,
            fetchedAtMillis = meta?.optLong("fetchedAtMillis", 0L) ?: 0L,
            providers = AiCatalogMerge.merge(AiProviderCatalog.all, parsed.providers),
        )
    }

    /** Synchronous network refresh. Call from an IO dispatcher. */
    fun refresh(): AiCatalogRefreshResult {
        val raw = fetch(catalogUrl) ?: return AiCatalogRefreshResult.Unavailable("offline")
        val expectedSha = fetch(shaUrl)?.trim()
        if (expectedSha.isNullOrBlank()) return AiCatalogRefreshResult.Unavailable("missing sha256")
        if (!verifySha(raw, expectedSha)) return AiCatalogRefreshResult.Unavailable("sha256 mismatch")

        val envelope = AiCatalogCodec.parse(raw).getOrNull()
            ?: return AiCatalogRefreshResult.Unavailable("malformed catalog")
        val problems = AiCatalogValidator.validateRemote(envelope.providers)
        if (problems.isNotEmpty()) return AiCatalogRefreshResult.Unavailable("invalid catalog: ${problems.first()}")

        val previous = cached()
        val merged = AiCatalogMerge.merge(AiProviderCatalog.all, envelope.providers)
        persist(envelope)

        return if (previous != null && previous.version == envelope.version) {
            AiCatalogRefreshResult.UpToDate
        } else {
            AiCatalogRefreshResult.Updated(envelope.version, merged.size)
        }
    }

    private fun persist(envelope: com.mobileharness.code.model.AiCatalogEnvelope) {
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, "ai_catalog.json.tmp")
            temporary.writeText(AiCatalogCodec.toJson(envelope.providers, envelope.version, envelope.updatedAt))
            if (!temporary.renameTo(catalogFile)) {
                temporary.copyTo(catalogFile, overwrite = true)
                temporary.delete()
            }
            metaFile.writeText(org.json.JSONObject().put("fetchedAtMillis", System.currentTimeMillis()).toString())
        }
    }

    /** Downloads a small metadata document over HTTPS with redirects disabled and a size cap. */
    private fun fetch(urlString: String): String? {
        if (!urlString.startsWith("https://")) return null
        return runCatching {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code !in 200..299) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { reader ->
                buildString {
                    val buffer = CharArray(8192)
                    while (length < MAX_CATALOG_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_CATALOG_CHARS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                }
            }
            connection.disconnect()
            body.ifBlank { null }
        }.getOrNull()
    }

    private fun verifySha(raw: String, expectedHex: String): Boolean {
        val expected = expectedHex.lowercase()
        if (!expected.matches(Regex("[0-9a-f]{64}"))) return false
        return sha256Hex(raw.toByteArray(Charsets.UTF_8)) == expected
    }

    private companion object {
        const val MAX_CATALOG_CHARS = 1_000_000
    }
}
