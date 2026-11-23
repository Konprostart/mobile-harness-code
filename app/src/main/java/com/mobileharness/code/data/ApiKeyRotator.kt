package com.mobileharness.code.data

import com.mobileharness.code.network.CompletionFailureKind
import org.json.JSONObject

/**
 * Per-provider, per-key rotation policy. Complements the existing model/provider [ProviderRotation]
 * by rotating **API keys within a single provider's pool** instead of switching providers.
 *
 * Key invariants (all fail-closed):
 *  - Keys are scoped to one provider id; a rotation decision is computed over that provider's key
 *    list only, so one provider can never use another provider's key.
 *  - An invalid key (401/403) or an exhausted account (402/no credit) is disabled and never retried,
 *    so the chain cannot loop forever on a dead credential.
 *  - A rate-limit (429) or transient server error (5xx) moves to another key but keeps the current
 *    one enabled (it may recover after a cooldown).
 *  - Transport failures (timeout/network) and bad-request errors never rotate keys: they are not
 *    caused by the key.
 */
sealed interface KeyRotationDecision {
    /** The failed key should be disabled, then retried with [nextKeyId]. */
    data class DisableAndRetry(val nextKeyId: String) : KeyRotationDecision

    /** The failed key should be disabled and no other enabled key is available. */
    data class DisableAndStop(val reason: String) : KeyRotationDecision

    /** Keep the failed key enabled, but try [nextKeyId] for the immediate retry. */
    data class RetryOtherKey(val nextKeyId: String) : KeyRotationDecision

    /** No rotation: the failure is not key-related, so keep using the same key. */
    data object KeepKey : KeyRotationDecision
}

object ApiKeyRotator {
    /** Enabled keys in deterministic order: active first, then name. */
    fun orderedUsableKeys(keys: List<ApiKeyInfo>): List<ApiKeyInfo> =
        keys.filter(ApiKeyInfo::enabled)
            .sortedWith(compareByDescending<ApiKeyInfo>(ApiKeyInfo::isActive).thenBy { it.name.lowercase() })

    /** The next enabled key id after excluding [excludedIds], or null when exhausted. */
    fun nextUsableKeyId(keys: List<ApiKeyInfo>, excludedIds: Set<String> = emptySet()): String? =
        orderedUsableKeys(keys).firstOrNull { it.id !in excludedIds }?.id

    /** Auth (invalid key) and quota-exhausted (no credit) are the only key-fatal failures. */
    fun shouldDisable(kind: CompletionFailureKind): Boolean =
        kind == CompletionFailureKind.AUTH || kind == CompletionFailureKind.QUOTA_EXCEEDED

    /** Failures worth rotating the key for. Transport/bad-request errors are deliberately excluded. */
    fun shouldRotateKey(kind: CompletionFailureKind): Boolean =
        kind == CompletionFailureKind.AUTH ||
            kind == CompletionFailureKind.QUOTA_EXCEEDED ||
            kind == CompletionFailureKind.RATE_LIMITED ||
            kind == CompletionFailureKind.SERVER_ERROR

    /**
     * Computes the rotation decision for a failed key. Pure and deterministic: it never mutates the
     * vault — the caller applies [KeyRotationDecision] via `ApiKeyVault.setEnabled` / `activate`.
     */
    fun decide(
        failedKeyId: String,
        kind: CompletionFailureKind,
        keys: List<ApiKeyInfo>,
    ): KeyRotationDecision {
        if (shouldDisable(kind)) {
            val next = nextUsableKeyId(keys, excludedIds = setOf(failedKeyId))
            return if (next != null) KeyRotationDecision.DisableAndRetry(next)
            else KeyRotationDecision.DisableAndStop("no other enabled API key")
        }
        if (shouldRotateKey(kind)) {
            val next = nextUsableKeyId(keys, excludedIds = setOf(failedKeyId))
            return if (next != null) KeyRotationDecision.RetryOtherKey(next)
            else KeyRotationDecision.KeepKey
        }
        return KeyRotationDecision.KeepKey
    }
}

/** Per-key usage ledger: success/failure counts plus the last failure kind (never the secret). */
data class ApiKeyStats(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastFailureKind: String? = null,
) {
    fun recordSuccess(): ApiKeyStats = copy(successCount = successCount + 1, consecutiveFailures = 0, lastFailureKind = null)
    fun recordFailure(kind: CompletionFailureKind): ApiKeyStats =
        copy(failureCount = failureCount + 1, consecutiveFailures = consecutiveFailures + 1, lastFailureKind = kind.name)

    fun toJson(): JSONObject = JSONObject()
        .put("success", successCount)
        .put("failure", failureCount)
        .put("consecutive", consecutiveFailures)
        .put("lastKind", lastFailureKind ?: JSONObject.NULL)

    companion object {
        fun fromJson(raw: JSONObject): ApiKeyStats = ApiKeyStats(
            successCount = raw.optInt("success", 0),
            failureCount = raw.optInt("failure", 0),
            consecutiveFailures = raw.optInt("consecutive", 0),
            lastFailureKind = raw.optString("lastKind").takeIf(String::isNotBlank),
        )
    }
}

/** Accumulates and encodes the per-provider key stats ledger. Pure (no Android), fully testable. */
object ApiKeyStatsLedger {
    fun toJson(ledger: Map<String, ApiKeyStats>): JSONObject {
        val root = JSONObject()
        ledger.forEach { (keyId, stats) -> root.put(keyId, stats.toJson()) }
        return root
    }

    fun fromJson(raw: String): Map<String, ApiKeyStats> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, ApiKeyStats>()
        root.keys().forEach { keyId ->
            root.optJSONObject(keyId)?.let { result[keyId] = ApiKeyStats.fromJson(it) }
        }
        return result
    }
}
