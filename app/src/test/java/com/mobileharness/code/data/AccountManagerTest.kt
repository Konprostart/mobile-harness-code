package com.mobileharness.code.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountManagerTest {
    @Test
    fun `deriving the same password and salt yields a verifiable hash`() {
        val salt = AccountCrypto.newSalt()
        val hash = AccountCrypto.derive("correct horse battery".toCharArray(), salt, iterations = 1_000)
        assertTrue(AccountCrypto.verify(hash, AccountCrypto.derive("correct horse battery".toCharArray(), salt, iterations = 1_000)))
    }

    @Test
    fun `a wrong password does not verify`() {
        val salt = AccountCrypto.newSalt()
        val hash = AccountCrypto.derive("right-password".toCharArray(), salt, iterations = 1_000)
        assertFalse(AccountCrypto.verify(hash, AccountCrypto.derive("wrong-password".toCharArray(), salt, iterations = 1_000)))
    }

    @Test
    fun `different salts produce different hashes for the same password`() {
        val password = "same-password".toCharArray()
        val first = AccountCrypto.derive(password, AccountCrypto.newSalt(), iterations = 1_000)
        val second = AccountCrypto.derive(password, AccountCrypto.newSalt(), iterations = 1_000)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `verifier rejects a different length hash`() {
        assertFalse(AccountCrypto.verify(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    @Test
    fun `base64 round trip preserves bytes`() {
        val bytes = ByteArray(32) { it.toByte() }
        assertTrue(bytes.contentEquals(AccountCrypto.decode(AccountCrypto.encode(bytes))))
    }

    @Test
    fun `username validation rejects blanks and overlong names`() {
        assertNull(AccountPolicy.validateUsername("Konprostart"))
        assertNull(AccountPolicy.validateUsername("  ab  "))
        assertNotNull(AccountPolicy.validateUsername(""))
        assertNotNull(AccountPolicy.validateUsername("a"))
        assertNotNull(AccountPolicy.validateUsername("x".repeat(61)))
    }

    @Test
    fun `password validation enforces minimum and maximum length`() {
        assertNull(AccountPolicy.validatePassword("long-enough".toCharArray()))
        assertNotNull(AccountPolicy.validatePassword("short".toCharArray()))
        assertNotNull(AccountPolicy.validatePassword(CharArray(257) { 'x' }))
    }
}
