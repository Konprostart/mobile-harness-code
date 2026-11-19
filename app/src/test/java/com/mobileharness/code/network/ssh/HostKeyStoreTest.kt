package com.mobileharness.code.network.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyStoreTest {
    @Test
    fun emptyInputMatchesKnownSha256Vector() {
        // SHA-256("") is the well-known NIST empty-string vector.
        assertEquals(
            "SHA256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HostKeyStore.fingerprint(byteArrayOf()),
        )
    }

    @Test
    fun abcInputMatchesKnownSha256Vector() {
        assertEquals(
            "SHA256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
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
