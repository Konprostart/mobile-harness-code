package com.mobileharness.code.network.ssh

import java.security.MessageDigest
import java.util.Base64

/**
 * Outcome of a Trust-On-First-Use (TOFU) host-key comparison.
 *
 *  - [AcceptFirst]: no fingerprint has been pinned yet — the caller must show the fingerprint
 *    and get explicit user confirmation before any credential is sent.
 *  - [Match]: the presented fingerprint equals the pinned one — proceed.
 *  - [Mismatch]: the fingerprint changed since first use — fail-closed (possible MITM or a
 *    legitimate server re-key), and let the user manually reset the pin.
 */
sealed interface TofuDecision {
    object AcceptFirst : TofuDecision
    object Match : TofuDecision
    data class Mismatch(val pinned: String, val presented: String) : TofuDecision
}

/**
 * Pure TOFU helpers. The fingerprint is the base64 SHA-256 of the host key's encoded bytes,
 * formatted `SHA256:…` for readability. The digest is computed from the same source on every
 * connect (sshj's `PublicKey.getEncoded()`), so the comparison is internally consistent even
 * though it does not match the wire-format hash `ssh-keygen` prints.
 *
 * Nothing here touches Android or the network, so the decision logic is unit-testable with
 * known SHA-256 vectors.
 */
object HostKeyStore {
    /** Formats a SHA-256 fingerprint from raw host-key bytes. */
    fun fingerprint(hostKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(hostKey)
        return "SHA256:" + Base64.getEncoder().encodeToString(digest)
    }

    /**
     * Compares the presented fingerprint against the pinned one. A null/blank pin means first
     * use ([TofuDecision.AcceptFirst]); otherwise equality decides between Match and Mismatch.
     */
    fun decide(pinned: String?, presented: String): TofuDecision {
        val known = pinned?.takeIf(String::isNotBlank)
        return when {
            known == null -> TofuDecision.AcceptFirst
            known == presented -> TofuDecision.Match
            else -> TofuDecision.Mismatch(known, presented)
        }
    }
}
