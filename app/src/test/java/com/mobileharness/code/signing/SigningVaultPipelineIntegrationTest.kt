package com.mobileharness.code.signing

import com.mobileharness.code.runtime.ReleaseSigning
import com.mobileharness.code.runtime.ReleaseSigningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves the Key Vault plugs into the existing release pipeline without changing it:
 * a vault-managed keystore is resolved to the exact [ReleaseSigningConfig] the guest
 * `apksigner` pipeline already consumes, and the same identity also signs directly
 * through the in-process `apksig` engine.
 */
class SigningVaultPipelineIntegrationTest {

    @Test
    fun `vault key resolves to a release signing config and signs an APK`() {
        val dir = Fixtures.tempDir("vault-pipeline")
        val repo = KeyRepository(dir, Fixtures.FakeCipher())
        val record = repo.createGenerated(
            KeystoreTools.KeyGenSpec(
                alias = "release",
                password = "store-secret-1".toCharArray(),
                commonName = "Mobile Harness Code",
            ),
            displayName = "Pipeline key",
        )

        // 1. The exact mapping MainViewModel.runReleasePipelineWithVaultKey performs.
        val (store, keys) = repo.passwords(record)
        val file = repo.keystoreFile(record)
        val keyPassword = keys[record.primaryAlias] ?: store
        val config = ReleaseSigningConfig(
            keystorePath = file.absolutePath,
            keyAlias = record.primaryAlias,
            storePassword = String(store),
            keyPassword = String(keyPassword),
        )

        assertEquals("release", config.keyAlias)
        assertTrue(File(config.keystorePath).isFile)
        // Passwords must travel through env vars, never on the command line.
        val args = ReleaseSigning.signArgs(config, "aligned.apk", "signed.apk").joinToString(" ")
        assertTrue(args.contains("--ks-pass env:" + ReleaseSigning.STORE_PASS_ENV))
        assertTrue(args.contains("--key-pass env:" + ReleaseSigning.KEY_PASS_ENV))
        assertFalse(args.contains("store-secret-1"))

        // 2. The same identity signs directly via apksig (in-process engine).
        val identity = repo.resolveIdentity(record)
        val outcome = SigningEngine.signApk(
            Fixtures.apkFile(dir, "input.apk"),
            File(dir, "signed.apk"),
            identity,
            SchemeOptions(v1 = true, v2 = true, v3 = true),
            minSdk = 26,
        )
        assertTrue(outcome.report.verified)
        assertEquals(record.fingerprintSha256, outcome.report.signers.first().sha256)
    }
}
