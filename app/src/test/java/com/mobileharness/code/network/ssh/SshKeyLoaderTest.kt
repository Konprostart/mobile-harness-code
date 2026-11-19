package com.mobileharness.code.network.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyLoaderTest {
    @Test
    fun detectRecognizesHeaders() {
        assertEquals(SshKeyKind.OPENSSH_V1, SshKeyLoader.detect("-----BEGIN OPENSSH PRIVATE KEY-----\nfoo"))
        assertEquals(SshKeyKind.RSA_PKCS1, SshKeyLoader.detect("-----BEGIN RSA PRIVATE KEY-----\nfoo"))
        assertEquals(SshKeyKind.ENCRYPTED_PKCS8, SshKeyLoader.detect("-----BEGIN ENCRYPTED PRIVATE KEY-----\nfoo"))
        assertEquals(SshKeyKind.EC, SshKeyLoader.detect("-----BEGIN EC PRIVATE KEY-----\nfoo"))
        assertEquals(SshKeyKind.PKCS8, SshKeyLoader.detect("-----BEGIN PRIVATE KEY-----\nfoo"))
        assertEquals(SshKeyKind.UNKNOWN, SshKeyLoader.detect("not a key"))
    }

    @Test
    fun normalizeAddsTrailingNewlineWhenMissing() {
        assertEquals("abc\n", SshKeyLoader.normalize("  abc  "))
        assertEquals("abc\n", SshKeyLoader.normalize("abc\n"))
    }

    @Test
    fun loadRejectsUnsupportedInput() {
        val result = SshKeyLoader.load("garbage", null)
        assertTrue(result is SshKeyLoader.Result.Failure)
    }

    @Test
    fun loadParsesUnencryptedPkcs1RsaKey() {
        val pem = RSA_PKCS1_FIXTURE
        val result = SshKeyLoader.load(pem, null)
        assertTrue("expected Success but got $result", result is SshKeyLoader.Result.Success)
    }

    @Test
    fun loadIgnoresPassphraseForUnencryptedKey() {
        // PKCS#1 keys are not encrypted, so a passphrase is simply ignored; the key still parses.
        val result = SshKeyLoader.load(RSA_PKCS1_FIXTURE, "irrelevant")
        assertTrue(result is SshKeyLoader.Result.Success)
    }

    private companion object {
        // Throwaway RSA-2048 PKCS#1 test key (generated for this test only; never used anywhere).
        val RSA_PKCS1_FIXTURE = """
-----BEGIN RSA PRIVATE KEY-----
MIIEogIBAAKCAQEAu1cXeOuXZAFrW/tOzYltIC5JnzJAOhKecHC0bBNWoKqF7Ooo
YlClgwFlQ1Or9SEoMaEvENj0920ifLVKcvHhefcu832akhGPTFNgn5+Grzx3+gj1
3WdwSn/ERz9iFXnjaieP2gObZe+11PEanR/ZU1HL/+64Z44M+VFw8Mql27W+/1Uh
wpFwCGpJ6Zc23r9e6jch+auHupUi8NBNSHIJ/exSKgLrhj7yHzbnJAbAfs+Gazx1
B4wcWHGRvn83WGYd1iLoCBa6dguIcS6pejde0SM0YyMdIMwsAI1LBJ18X1nfvHPS
iT8wsBeKJt9tJyBHDijJkJ74uvr5OIPiPGFKAwIDAQABAoH/bE3tQ/rviuFHL+YZ
qLnKXNBlv6NNfkVFU7vUkqabbkcnJdA5iJ0XEqW75TB6OTzx/78vdo33ERQG3BHy
+IDd2aUNf7cdpVSBg56wPZf8RVKwCd0kRtuGD4vN7WK3UbMlxjkY8PEk5cmvVWwN
9shy27huN4fLFBMLbReoM4z0Vg++Z4UpfWwbN+yxG6ZoTxo3L1gP75nzKRQVDYDo
BmEPZnoCmXvnlS3mH3+6Oc8YjV5ONhs5aCE+CbC+4bWw5dXzeW6UEqjPCF5K+wEj
2Ea4qmmmaogtY803N93O6wwrd04LRqz6uxc1H+7WeV/dRGJcul20LayHz4hPBxtg
D8whAoGBAOxmZZASV1rlalZzW9LYwJeNq0xbeb2oBoD1Xu7L4Xfg/DsldSvGCQEb
CC/S17ofNxwQEPFm0BKY13C6vqfz84OZZHj36CmWJ9gYggC4X8hOJbCG00s14sAv
pvArKBabZnOQKB/QL2caUgdKi959mifL4RV1bzA78JWwzFbQT1KvAoGBAMrfZfDO
WeqDz6x8j0F/7JL4+9bdC+7PDHuzgry1Juj7JQBmSyWwf3yZ+xUy2bYuXB75l7vS
SMDPSTTIJ1pzGqXPG798vlaYqgtrL+kElOD9kbnHPX8Gn7memHigoH0X4vkBiYbG
xz7Vh05DvjcQ4LWNIOcObNPEHvBmIhc9EaLtAoGBAJCugs5QS2J4MkLCy5oZypV2
wTY0bifjJEfc5MMlyNKDwhu46yNlSrGAh9wtIJbq7+Ge+5+jGwmmvYRi5rkIFyud
v95Ba7e4DJ63MSmbrhN7sJl/iuXnDuYsw/ENmLVtWqCzw7rkwX5Kn5WSzFPZb3WX
1CQ8AVb1+GLFF+qyBzpFAoGAIvhpWR4o77zy8neyNgc4H4md18bYxLyaGUSzV5Ye
c/C+yxfXAlMvVqowVtIiJz5MkOZTWoQAGIRiyR9rcTMAN+7AiLyXDtRYE4nRg2hP
xYzQLucCWcApjlShUcHWiFmyvVufekUPmWwzW7Ffu5xEL+d48XtSFRrsfmIMpFbZ
kG0CgYEAt3fRApwrgpeMnwJbT1RzrP/JJsdNcouHVpwLgEw0Pg+hWplPkvOPQ8sy
7ZfpMNjRGP6XhzMVa/ktSe9yOPH5Uv5Rl8oZ4WzN4djv/f37GnNAeuxs0maQACaF
KVqbXTfMxrPW2uVqAHZwWGTn6RpO7j3vVd+CTsSYFHaPY6c3KSY=
-----END RSA PRIVATE KEY-----
""".trimIndent()
    }
}
