package com.mobileharness.code.network.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyStoreTest {
    @Test
    fun emptyInputMatchesKnownSha256Vector() {
        // SHA-256("") base64 is the well-known NIST empty-string vector.
        assertEquals(
            "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
            HostKeyStore.fingerprint(byteArrayOf()),
        )
    }

    @Test
    fun abcInputMatchesKnownSha256Vector() {
        assertEquals(
            "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
            HostKeyStore.fingerprint("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun decideAcceptsFirstUse() {
        assertEquals(TofuDecision.AcceptFirst, HostKeyStore.decide(null, "SHA256:x"))
        assertEquals(TofuDecision.AcceptFirst, HostKeyStore.decide("", "SHA256:x"))
        assertEquals(TofuDecision.AcceptFirst, HostKeyStore.decide("   ", "SHA256:x"))
    }

    @Test
    fun decideMatchesIdenticalFingerprints() {
        assertEquals(TofuDecision.Match, HostKeyStore.decide("SHA256:abc", "SHA256:abc"))
    }

    @Test
    fun decideFlagsMismatchWithBothValues() {
        val decision = HostKeyStore.decide("SHA256:pinned", "SHA256:presented")
        assertTrue(decision is TofuDecision.Mismatch)
        val mismatch = decision as TofuDecision.Mismatch
        assertEquals("SHA256:pinned", mismatch.pinned)
        assertEquals("SHA256:presented", mismatch.presented)
    }
}
