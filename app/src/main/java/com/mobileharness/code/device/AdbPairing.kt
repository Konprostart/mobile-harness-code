package com.mobileharness.code.device

/**
 * Wireless-debugging SPAKE2 pairing (Android 11+).
 *
 * To use [WirelessAdbExecutor], the app's ADB key must first be registered with the device's
 * `adbd`. That registration is the SPAKE2 pairing exchange: the user reads a 6-digit code from the
 * Wireless Debugging screen and the client performs a Curve25519 SPAKE2 handshake with AES-256-GCM
 * to deliver its public key.
 *
 * This class is an intentional, honest stub: the full SPAKE2 exchange requires the Curve25519
 * (X25519) primitive plus HKDF/AES-GCM, which are not exposed portably through Android's standard
 * `java.security` API. Rather than ship a hand-rolled and therefore unverifiable SPAKE2 (which the
 * project rules explicitly forbid), pairing is left to a proven host-side tool:
 *
 *     adb pair <host>:<pairing-port> <6-digit-code>
 *     adb connect <host>:<port>
 *
 * until a vetted, license-clean X25519/HKDF implementation is added. The executor still works
 * end-to-end once a key is paired; the pairing UI reports this status honestly instead of
 * pretending to pair.
 */
object AdbPairing {
    /** True only when the SPAKE2 pairing exchange is actually implemented (currently never). */
    const val SUPPORTED = false

    /** Human-readable status for the Phone Control UI. */
    fun status(): String =
        "Wireless ADB pairing (SPAKE2) is not implemented in this build. Pair once with a host-side " +
            "'adb pair', then wireless execution works with the paired key."
}
