package com.mobileharness.code.model

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote AI provider catalog: the additional, hostable layer that extends the bundled
 * [AiProviderCatalog] without shipping a new APK.
 *
 * Security model (fail-closed):
 *  - The remote document is **metadata only**. It has no code, no secrets, and no effect on the
 *    user's saved API keys or configurations.
 *  - Every URL must be `https://` or a loopback address; credentials in URLs (`user@host`) are
 *    rejected; enum values are validated so an arbitrary string can never reach a request.
 *  - The document is capped in size and its SHA-256 is pinned before use (see the fetcher).
 *  - A malformed remote document is dropped entirely and the bundled catalog keeps serving.
 */

/** Versioned remote catalog envelope. */
data class AiCatalogEnvelope(
    val schemaVersion: Int,
    val version: Long,
    val updatedAt: String,
    val providers: List<AiProvider>,
)

object AiCatalogCodec {
    const val SCHEMA_VERSION = 1
    private const val MAX_JSON_CHARS = 1_000_000

    /** Parses a remote catalog document; null (and a reason) when it is malformed or oversized. */
    fun parse(json: String): Result<AiCatalogEnvelope> {
        if (json.length > MAX_JSON_CHARS) return Result.failure(IllegalArgumentException("catalog too large"))
        return runCatching {
            val root = JSONObject(json.trim())
            val schemaVersion = root.optInt("schemaVersion", 0)
            if (schemaVersion != SCHEMA_VERSION) {
                throw IllegalArgumentException("unsupported schemaVersion $schemaVersion")
            }
            val providers = root.optJSONArray("providers")
                ?: throw IllegalArgumentException("missing providers array")
            val parsed = (0 until providers.length()).mapNotNull { index ->
                val item = providers.optJSONObject(index) ?: return@mapNotNull null
                parseProvider(item)
            }
            if (parsed.size != providers.length()) {
                throw IllegalArgumentException("one or more providers are malformed")
            }
            AiCatalogEnvelope(
                schemaVersion = schemaVersion,
                version = root.optLong("version", 0L),
                updatedAt = root.optString("updatedAt", ""),
                providers = parsed,
            )
        }
    }

    private fun parseProvider(item: JSONObject): AiProvider {
        val category = enumValue<AiProviderCategory>(item.optString("category"))
        val wireProtocol = enumValue<AiWireProtocol>(item.optString("wireProtocol"))
        val authType = enumValue<AiAuthType>(item.optString("authType"))
        val apiFormats = enumList<AiWireProtocol>(item.optJSONArray("apiFormats"))
        val capabilities = enumList<AiCapability>(item.optJSONArray("capabilities"))
        return AiProvider(
            id = item.optString("id"),
            name = item.optString("name"),
            category = category,
            baseUrl = item.optString("baseUrl"),
            wireProtocol = wireProtocol,
            defaultModel = item.optString("defaultModel"),
            authType = authType,
            apiFormats = apiFormats,
            capabilities = capabilities,
            docsUrl = item.optString("docsUrl"),
            notes = item.optString("notes"),
            official = item.optBoolean("official", true),
        )
    }

    /** Serializes providers back into the documented remote JSON shape (used by tests + generator). */
    fun toJson(providers: List<AiProvider>, version: Long, updatedAt: String): String {
        val array = JSONArray()
        providers.forEach { provider ->
            val formats = JSONArray()
            provider.apiFormats.sortedBy { it.name }.forEach { formats.put(it) }
            val capabilities = JSONArray()
            provider.capabilities.sortedBy { it.name }.forEach { capabilities.put(it.name) }
            array.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("category", provider.category.name)
                    .put("baseUrl", provider.baseUrl)
                    .put("wireProtocol", provider.wireProtocol.name)
                    .put("defaultModel", provider.defaultModel)
                    .put("authType", provider.authType.name)
                    .put("apiFormats", formats)
                    .put("capabilities", capabilities)
                    .put("docsUrl", provider.docsUrl)
                    .put("notes", provider.notes)
                    .put("official", provider.official),
            )
        }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("version", version)
            .put("updatedAt", updatedAt)
            .put("providers", array)
            .toString()
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown enum value '$raw' for ${T::class.simpleName}")

    private inline fun <reified T : Enum<T>> enumList(array: JSONArray?): Set<T> {
        if (array == null) return emptySet()
        val result = mutableSetOf<T>()
        for (index in 0 until array.length()) result += enumValue<T>(array.optString(index))
        return result
    }
}

object AiCatalogValidator {
    fun validateRemote(providers: List<AiProvider>): List<String> {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (provider in providers) {
            val id = provider.id.trim()
            if (!id.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
                problems += "invalid provider id '$id'"
            }
            if (!seen.add(id)) problems += "duplicate provider id '$id'"
            if (provider.name.isBlank()) problems += "'$id' has a blank name"
            if (provider.category !in AiProviderCategory.entries) problems += "'$id' invalid category"
            if (provider.wireProtocol !in AiWireProtocol.entries) problems += "'$id' invalid wire protocol"
            if (provider.authType !in AiAuthType.entries) problems += "'$id' invalid auth type"
            if (provider.defaultModel.isBlank() && !provider.needsAdapter && provider.category != AiProviderCategory.CUSTOM) {
                problems += "'$id' has no default model"
            }
            validateUrl(provider.baseUrl, "base URL", id, problems)
            if (provider.docsUrl.isNotBlank()) validateHttpsUrl(provider.docsUrl, "docs URL", id, problems)
            for (format in provider.apiFormats) if (format !in AiWireProtocol.entries) problems += "'$id' invalid api format"
            for (capability in provider.capabilities) if (capability !in AiCapability.entries) problems += "'$id' invalid capability"
        }
        return problems
    }

    private fun validateUrl(url: String, label: String, id: String, problems: MutableList<String>) {
        if (url.isBlank()) {
            if (label == "base URL") problems += "'$id' has a blank base URL"
            return
        }
        // Reject userinfo (credential smuggling) and require https:// or a loopback http://.
        if (url.contains('@')) {
            problems += "'$id' $label contains credentials"
            return
        }
        val ok = url.startsWith("https://") ||
            url.startsWith("http://localhost") ||
            url.startsWith("http://127.0.0.1")
        if (!ok) problems += "'$id' $label must be https:// or a loopback address"
    }

    private fun validateHttpsUrl(url: String, label: String, id: String, problems: MutableList<String>) {
        if (!url.startsWith("https://")) problems += "'$id' $label must be https://"
        if (url.contains('@')) problems += "'$id' $label contains credentials"
    }
}

object AiCatalogMerge {
    /**
     * Merges a remote overlay onto the bundled base. Remote entries with an existing id replace
     * that provider's metadata in place; new ids are appended. The bundled catalog can never be
     * reduced, and the merge never touches user configuration or secrets (none are present).
     */
    fun merge(bundled: List<AiProvider>, remote: List<AiProvider>): List<AiProvider> {
        val remoteById = remote.associateBy { it.id }
        val merged = mutableListOf<AiProvider>()
        val usedIds = mutableSetOf<String>()
        for (provider in bundled) {
            usedIds += provider.id
            merged += remoteById[provider.id] ?: provider
        }
        for (provider in remote) {
            if (usedIds.add(provider.id)) merged += provider
        }
        return merged
    }
}

/** Hex-encoded SHA-256 of [bytes], used to pin a remote catalog before it is applied. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}
