package com.mobileharness.code.runtime

import java.io.File
import java.security.MessageDigest

/**
 * Pure, Android-free core of the release pipeline: Build → Sign → Verify → Checksum.
 * The signing passphrases are passed to `apksigner` through environment variables
 * (`env:`), never on the command line, so they cannot leak into `ps` output or logs.
 */
data class ReleaseSigningConfig(
    val keystorePath: String,
    val keyAlias: String,
    val storePassword: String,
    val keyPassword: String,
)

object ReleaseSigning {
    const val STORE_PASS_ENV = "MH_RELEASE_STORE_PASS"
    const val KEY_PASS_ENV = "MH_RELEASE_KEY_PASS"

    fun zipalignArgs(input: String, output: String): List<String> =
        listOf("-f", "-p", "4", input, output)

    fun signArgs(config: ReleaseSigningConfig, input: String, output: String): List<String> = listOf(
        "sign",
        "--ks", config.keystorePath,
        "--ks-key-alias", config.keyAlias,
        "--ks-pass", "env:$STORE_PASS_ENV",
        "--key-pass", "env:$KEY_PASS_ENV",
        // All three schemes: V1 for legacy OEM installers, V2 for 7.0+, V3 for
        // Android 16 key-rotation / current installers (see issue #102).
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--out", output,
        input,
    )

    fun signEnvironment(config: ReleaseSigningConfig): Map<String, String> = mapOf(
        STORE_PASS_ENV to config.storePassword,
        KEY_PASS_ENV to config.keyPassword,
    )

    fun verifyArgs(apk: String): List<String> = listOf("verify", "--verbose", "--print-certs", apk)
}

enum class ReleasePipelineStage {
    BUILD, ZIPALIGN, SIGN, VERIFY, CHECKSUM, COMPLETE, FAILED,
}

data class ReleaseCommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
) {
    val succeeded: Boolean get() = exitCode == 0
}

/** Runs one release command (binary + args) with an optional environment. */
fun interface ReleaseCommandRunner {
    fun run(binary: String, args: List<String>, env: Map<String, String>): ReleaseCommandResult
}

data class ReleaseStageResult(
    val stage: ReleasePipelineStage,
    val success: Boolean,
    val message: String,
)

data class ReleasePipelineOutcome(
    val success: Boolean,
    val signedApk: String? = null,
    val sha256: String? = null,
    val stages: List<ReleaseStageResult> = emptyList(),
)

object ReleasePipelineEngine {
    fun run(
        runner: ReleaseCommandRunner,
        unsignedApk: String,
        outputDir: String,
        signing: ReleaseSigningConfig,
        buildCommand: List<String>? = null,
        zipalignBinary: String = "zipalign",
        apksignerBinary: String = "apksigner",
        checksum: (String) -> String = { sha256Hex(File(it)) },
    ): ReleasePipelineOutcome {
        val stages = mutableListOf<ReleaseStageResult>()
        fun fail(message: String): ReleasePipelineOutcome {
            stages += ReleaseStageResult(ReleasePipelineStage.FAILED, false, message)
            return ReleasePipelineOutcome(false, stages = stages)
        }

        if (buildCommand != null && buildCommand.isNotEmpty()) {
            val result = runner.run(buildCommand.first(), buildCommand.drop(1), emptyMap())
            stages += ReleaseStageResult(
                ReleasePipelineStage.BUILD,
                result.succeeded,
                "release build exited ${result.exitCode}",
            )
            if (!result.succeeded) return fail(result.stderr.ifBlank { "release build failed" })
        }

        val alignedApk = "$outputDir/aligned.apk"
        val zipalign = runner.run(zipalignBinary, ReleaseSigning.zipalignArgs(unsignedApk, alignedApk), emptyMap())
        stages += ReleaseStageResult(
            ReleasePipelineStage.ZIPALIGN,
            zipalign.succeeded,
            "zipalign exited ${zipalign.exitCode}",
        )
        if (!zipalign.succeeded) return fail(zipalign.stderr.ifBlank { "zipalign failed" })

        val signedApk = "$outputDir/signed.apk"
        val sign = runner.run(
            apksignerBinary,
            ReleaseSigning.signArgs(signing, alignedApk, signedApk),
            ReleaseSigning.signEnvironment(signing),
        )
        stages += ReleaseStageResult(
            ReleasePipelineStage.SIGN,
            sign.succeeded,
            "apksigner sign exited ${sign.exitCode}",
        )
        if (!sign.succeeded) return fail(sign.stderr.ifBlank { "apksigner sign failed" })

        val verify = runner.run(apksignerBinary, ReleaseSigning.verifyArgs(signedApk), emptyMap())
        stages += ReleaseStageResult(
            ReleasePipelineStage.VERIFY,
            verify.succeeded,
            "apksigner verify exited ${verify.exitCode}",
        )
        if (!verify.succeeded) return fail(verify.stderr.ifBlank { "apksigner verify failed" })

        val digest = runCatching { checksum(signedApk) }.getOrElse { return fail("checksum failed: ${it.message}") }
        stages += ReleaseStageResult(ReleasePipelineStage.CHECKSUM, true, "sha256=$digest")
        stages += ReleaseStageResult(ReleasePipelineStage.COMPLETE, true, "release pipeline complete")

        return ReleasePipelineOutcome(true, signedApk, digest, stages)
    }
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
