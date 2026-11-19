package com.mobileharness.code.share

import java.security.SecureRandom

/**
 * Personal invite / referral codes, generated locally and stored device-only.
 *
 * Format: `MHC-XXXXXX` where `XXXXXX` is six characters drawn from a
 * Crockford-style alphabet that omits ambiguous glyphs (no `0`/`O`, no `1`/`I`/`L`).
 *
 * This is local-only scaffolding: the "Crypto Rewards" program is still "SOON",
 * so nothing here is uploaded anywhere and no wallet/RPC/Web3 code exists.
 */
object InviteCode {
    const val PREFIX = "MHC"
    const val CODE_LENGTH = 6

    /** Unambiguous alphabet: no 0/O, no 1/I/L. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private val secureRandom = SecureRandom()

    /** Generates a fresh `MHC-XXXXXX` code using a secure random source. */
    fun generate(): String = generate(secureRandom)

    /** Deterministic variant (caller-provided source) for tests and seeding. */
    fun generate(random: java.util.Random): String = buildString {
        append(PREFIX)
        append('-')
        repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** Normalizes user input: trimmed and uppercased. */
    fun normalize(raw: String): String = raw.trim().uppercase()

    /** True when [raw] is a well-formed invite code (case-insensitive). */
    fun isValid(raw: String): Boolean {
        val code = normalize(raw)
        if (code.length != PREFIX.length + 1 + CODE_LENGTH) return false
        if (!code.startsWith("$PREFIX-")) return false
        val body = code.substring(PREFIX.length + 1)
        return body.all { it in ALPHABET }
    }

    /** True when [code] is the user's own code (self-referral must be rejected). */
    fun isSelfReferral(code: String, ownCode: String): Boolean {
        if (code.isBlank() || ownCode.isBlank()) return false
        return normalize(code) == normalize(ownCode)
    }
}

/** Canonical download link + ready-to-send share message. */
object ShareLinks {
    /**
     * Canonical "latest" download URL. It is stable across releases, so a
     * shared link or QR code always points at the newest signed APK without
     * ever embedding a version number.
     */
    const val DOWNLOAD_URL =
        "https://github.com/Konprostart/mobile-harness-code/releases/latest/download/mobile-harness-online.apk"

    /**
     * Builds the plain-text message sent via `Intent.ACTION_SEND`: the download
     * link, a two-step install instruction, the inviter's code and a short
     * "rewards coming soon" note. Pure and unit-testable.
     */
    fun buildShareText(
        inviteCode: String,
        versionName: String?,
        downloadUrl: String = DOWNLOAD_URL,
        appName: String = "Mobile Harness Code",
    ): String = buildString {
        append(appName)
        append(" — independent AI dev app for your phone\n\n")
        append("1) Download the signed APK:\n")
        append(downloadUrl)
        append("\n")
        append("2) Open the downloaded file and allow installs from this source.\n\n")
        append("My invite code: ")
        append(if (inviteCode.isBlank()) "${InviteCode.PREFIX}-XXXXXX" else inviteCode.trim().uppercase())
        append("\n")
        if (!versionName.isNullOrBlank()) {
            append("(v")
            append(versionName)
            append(")\n")
        }
        append("\nCrypto rewards for active invites are coming soon.")
    }
}
