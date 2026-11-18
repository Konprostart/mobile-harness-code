package com.mobileharness.code.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReleasePipelineTest {
    private val config = ReleaseSigningConfig(
        keystorePath = "/root/upload.jks",
        keyAlias = "upload",
        storePassword = "store-secret",
        keyPassword = "key-secret",
    )

    @Test
    fun zipalignArgsAreStandard() {
        assertEquals(
            listOf("-f", "-p", "4", "in.apk", "out.apk"),
            ReleaseSigning.zipalignArgs("in.apk", "out.apk"),
        )
    }

    @Test
    fun signArgsEnableAllThreeSchemesAndNeverInlinePasswords() {
        val args = ReleaseSigning.signArgs(config, "aligned.apk", "signed.apk")
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("--v1-signing-enabled true"))
        assertTrue(joined.contains("--v2-signing-enabled true"))
        assertTrue(joined.contains("--v3-signing-enabled true"))
        assertTrue(joined.contains("--ks-pass env:${ReleaseSigning.STORE_PASS_ENV}"))
        assertTrue(joined.contains("--key-pass env:${ReleaseSigning.KEY_PASS_ENV}"))
        assertFalse(joined.contains("store-secret"))
        assertFalse(joined.contains("key-secret"))
        assertEquals("signed.apk", args[args.indexOf("--out") + 1])
    }

    @Test
    fun signEnvironmentCarriesOnlyPassphrases() {
        assertEquals(
            mapOf(
                ReleaseSigning.STORE_PASS_ENV to "store-secret",
                ReleaseSigning.KEY_PASS_ENV to "key-secret",
            ),
            ReleaseSigning.signEnvironment(config),
        )
    }

    @Test
    fun verifyArgsPrintCertsVerbose() {
        assertEquals(listOf("verify", "--verbose", "--print-certs", "signed.apk"), ReleaseSigning.verifyArgs("signed.apk"))
    }

    @Test
    fun sha256MatchesKnownValue() {
        val file = File.createTempFile("checksum", ".bin").apply { writeBytes("hello world".toByteArray()) }
        try {
            assertEquals(
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                sha256Hex(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun engineRunsBuildZipalignSignVerifyChecksumInOrder() {
        val root = Files.createTempDirectory("release-pipeline").toFile()
        try {
            File(root, "unsigned.apk").writeBytes(byteArrayOf(1, 2, 3))
            val invoked = mutableListOf<String>()
            val runner = ReleaseCommandRunner { binary, args, _ ->
                invoked += binary
                when (binary) {
                    "./gradlew" -> {
                        File(root, "unsigned.apk").writeBytes(byteArrayOf(1, 2, 3))
                        ReleaseCommandResult(0)
                    }
                    "zipalign" -> {
                        File(root, "aligned.apk").writeBytes(byteArrayOf(4, 5, 6))
                        ReleaseCommandResult(0)
                    }
                    "apksigner" -> {
                        if (args.first() == "sign") File(root, "signed.apk").writeBytes("signed".toByteArray())
                        ReleaseCommandResult(0)
                    }
                    else -> ReleaseCommandResult(0)
                }
            }
            val outcome = ReleasePipelineEngine.run(
                runner = runner,
                unsignedApk = File(root, "unsigned.apk").path,
                outputDir = root.path,
                signing = config,
                buildCommand = listOf("./gradlew", "assembleRelease"),
            )
            assertTrue(outcome.success)
            assertEquals(File(root, "signed.apk").path, outcome.signedApk)
            assertEquals(sha256Hex(File(root, "signed.apk")), outcome.sha256)
            assertEquals(listOf("./gradlew", "zipalign", "apksigner", "apksigner"), invoked)
            assertEquals(
                listOf(
                    ReleasePipelineStage.BUILD,
                    ReleasePipelineStage.ZIPALIGN,
                    ReleasePipelineStage.SIGN,
                    ReleasePipelineStage.VERIFY,
                    ReleasePipelineStage.CHECKSUM,
                    ReleasePipelineStage.COMPLETE,
                ),
                outcome.stages.map { it.stage },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun engineFailsClosedWhenVerifyFails() {
        val root = Files.createTempDirectory("release-pipeline-fail").toFile()
        try {
            val runner = ReleaseCommandRunner { binary, args, _ ->
                when (binary) {
                    "zipalign" -> { File(root, "aligned.apk").writeBytes(byteArrayOf(4, 5, 6)); ReleaseCommandResult(0) }
                    "apksigner" -> if (args.first() == "sign") {
                        File(root, "signed.apk").writeBytes("signed".toByteArray())
                        ReleaseCommandResult(0)
                    } else {
                        ReleaseCommandResult(1, stderr = "signature verification failed")
                    }
                    else -> ReleaseCommandResult(0)
                }
            }
            val outcome = ReleasePipelineEngine.run(
                runner = runner,
                unsignedApk = File(root, "unsigned.apk").path,
                outputDir = root.path,
                signing = config,
            )
            assertFalse(outcome.success)
            assertNull(outcome.signedApk)
            assertNull(outcome.sha256)
            assertTrue(outcome.stages.last().message.contains("verification failed"))
        } finally {
            root.deleteRecursively()
        }
    }
}
