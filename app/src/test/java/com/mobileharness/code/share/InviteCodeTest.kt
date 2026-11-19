package com.mobileharness.code.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeTest {

    @Test
    fun generatedCodeMatchesFormat() {
        repeat(200) {
            val code = InviteCode.generate()
            assertTrue("bad format: $code", InviteCode.isValid(code))
            assertTrue(code.startsWith("MHC-"))
            assertEquals(10, code.length)
        }
    }

    @Test
    fun generatedCodeUsesOnlyUnambiguousAlphabet() {
        repeat(200) {
            val body = InviteCode.generate().substring("MHC-".length)
            assertTrue(body.all { it in InviteCode.ALPHABET })
        }
    }

    @Test
    fun generateIsDeterministicGivenSeed() {
        val a = InviteCode.generate(java.util.Random(42))
        val b = InviteCode.generate(java.util.Random(42))
        assertEquals(a, b)
    }

    @Test
    fun isValidIsCaseInsensitive() {
        assertTrue(InviteCode.isValid("mhc-7k9q2p"))
        assertTrue(InviteCode.isValid("MHC-7K9Q2P"))
    }

    @Test
    fun isValidRejectsBadInput() {
        assertFalse(InviteCode.isValid(""))
        assertFalse(InviteCode.isValid("MHC-7K9Q2"))
        assertFalse(InviteCode.isValid("MHC-7K9Q2PP"))
        assertFalse(InviteCode.isValid("ABC-7K9Q2P"))
        assertFalse(InviteCode.isValid("MHC-7K9Q2O")) // 'O' not in alphabet
        assertFalse(InviteCode.isValid("MHC-7K9Q2I")) // 'I' not in alphabet
        assertFalse(InviteCode.isValid("MHC-7K9Q20")) // '0' not in alphabet
        assertFalse(InviteCode.isValid("MHC-7K9Q21")) // '1' not in alphabet
    }

    @Test
    fun normalizeTrimsAndUppercases() {
        assertEquals("MHC-7K9Q2P", InviteCode.normalize("  mhc-7k9q2p  "))
    }

    @Test
    fun selfReferralDetection() {
        assertTrue(InviteCode.isSelfReferral("MHC-7K9Q2P", "MHC-7K9Q2P"))
        assertTrue(InviteCode.isSelfReferral("mhc-7k9q2p", "MHC-7K9Q2P"))
        assertFalse(InviteCode.isSelfReferral("MHC-AAAAAA", "MHC-7K9Q2P"))
        assertFalse(InviteCode.isSelfReferral("", "MHC-7K9Q2P"))
        assertFalse(InviteCode.isSelfReferral("MHC-7K9Q2P", ""))
    }

    @Test
    fun shareTextEmbedsLinkCodeAndSteps() {
        val text = ShareLinks.buildShareText("MHC-7K9Q2P", "1.3.5")
        assertTrue(text.contains(ShareLinks.DOWNLOAD_URL))
        assertTrue(text.contains("MHC-7K9Q2P"))
        assertTrue(text.contains("1.3.5"))
        assertTrue(text.contains("1)"))
        assertTrue(text.contains("2)"))
        assertTrue(text.contains("allow installs from this source"))
        assertTrue(text.contains("coming soon"))
    }

    @Test
    fun shareTextUsesPlaceholderForBlankCode() {
        val text = ShareLinks.buildShareText("", null)
        assertTrue(text.contains("MHC-XXXXXX"))
    }
}
