package com.mobileharness.code.runtime

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Android/guest integration for the release pipeline. The deterministic
 * Build → Sign → Verify → Checksum sequence lives in [ReleasePipelineEngine];
 * this class supplies the guest-backed command runner and the phone-side sinks
 * (SAF export, Downloads save). GitHub publishing lives in [ReleaseGitHubUploader].
 */
object ReleaseGuestTools {
    const val BUILD_TOOLS_DIR = "/root/android-sdk/build-tools/35.0.0"
    const val APKSIGNER = "$BUILD_TOOLS_DIR/apksigner"
    const val ZIPALIGN = "$BUILD_TOOLS_DIR/zipalign"
}

/** Executes a single command inside the private Linux runtime (PRoot guest). */
class GuestReleaseCommandRunner(
    private val installer: RuntimeInstaller,
    private val proot: File,
    private val rootfs: File,
    private val workspace: File,
) : ReleaseCommandRunner {
    override fun run(binary: String, args: List<String>, env: Map<String, String>): ReleaseCommandResult {
        val process = installer.process(
            proot = proot,
            rootfs = rootfs,
            workspace = workspace,
            environment = env,
            guestCommand = listOf(binary) + args,
        )
        val exitCode = process.waitFor()
        val stdout = (process as? NativeSpawnProcess)
            ?.outputFile
            ?.takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrDefault("") }
            .orEmpty()
        return ReleaseCommandResult(exitCode, stdout = stdout)
    }
}

class ReleasePipelineRunner(private val context: Context) {
    /** The guest release-build command for a project (assembleRelease). */
    fun releaseBuildCommand(projectRoot: File, jvmArgs: String? = null): List<String> {
        val command = androidGradleCommand(projectRoot, "assembleRelease", jvmArgs)
        return listOf("/usr/bin/bash", "-lc", command)
    }

    /**
     * Runs the full pipeline for a project: release build in the guest, then
     * zipalign → apksigner sign → verify → checksum, saving the signed APK under
     * [outputDir]. Signing runs inside the private Linux runtime (the only place
     * `apksigner`/`zipalign` exist on the phone), so the pipeline operates on
     * guest paths and the signed artifact is copied back to the host at the end.
     * Fail-closed: any non-zero stage aborts and returns failure.
     */
    fun runProjectRelease(
        installer: RuntimeInstaller,
        projectRoot: File,
        signing: ReleaseSigningConfig,
        outputDir: File,
        jvmArgs: String? = null,
    ): ReleasePipelineOutcome {
        val installed = installer.installedRuntime()
        val workspace = findAndroidProjectRoot(projectRoot) ?: return ReleasePipelineOutcome(
            success = false,
            stages = listOf(ReleaseStageResult(ReleasePipelineStage.FAILED, false, "No Android project found under $projectRoot")),
        )
        outputDir.mkdirs()
        val runner = GuestReleaseCommandRunner(installer, installed.proot, installed.rootfs, workspace)

        val build = runner.run(
            binary = "/usr/bin/bash",
            args = listOf("-lc", androidGradleCommand(workspace, "assembleRelease", jvmArgs)),
            env = emptyMap(),
        )
        if (!build.succeeded) {
            return ReleasePipelineOutcome(
                success = false,
                stages = listOf(ReleaseStageResult(ReleasePipelineStage.BUILD, false, build.stdout.ifBlank { "release build failed" })),
            )
        }
        val unsigned = findReleaseApk(workspace) ?: return ReleasePipelineOutcome(
            success = false,
            stages = listOf(
                ReleaseStageResult(ReleasePipelineStage.BUILD, true, "release build succeeded"),
                ReleaseStageResult(ReleasePipelineStage.FAILED, false, "No release APK produced"),
            ),
        )

        val guestReleaseDir = "/workspace/.release-output"
        val hostReleaseDir = File(workspace, ".release-output").apply { mkdirs() }
        val outcome = ReleasePipelineEngine.run(
            runner = runner,
            unsignedApk = toGuestPath(workspace, unsigned),
            outputDir = guestReleaseDir,
            signing = signing,
            zipalignBinary = ReleaseGuestTools.ZIPALIGN,
            apksignerBinary = ReleaseGuestTools.APKSIGNER,
            // The engine's checksum runs on the host, so translate the guest path back.
            checksum = { guestPath -> sha256Hex(guestToHostFile(workspace, guestPath)) },
        )
        if (outcome.success) {
            runCatching {
                File(hostReleaseDir, "signed.apk").copyTo(File(outputDir, "signed.apk"), overwrite = true)
            }
        }
        return outcome.copy(stages = listOf(ReleaseStageResult(ReleasePipelineStage.BUILD, true, "release build succeeded")) + outcome.stages)
    }

    private fun toGuestPath(workspace: File, hostFile: File): String =
        "/workspace/" + hostFile.relativeTo(workspace).invariantSeparatorsPath

    private fun guestToHostFile(workspace: File, guestPath: String): File =
        File(workspace, guestPath.removePrefix("/workspace/"))

    /** Builds an `ACTION_CREATE_DOCUMENT` intent for exporting an artifact via SAF (e.g. Signiffia). */
    fun createSafExportIntent(artifact: File, suggestedName: String = artifact.name): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    /** Saves a signed APK to the shared Downloads collection (API 29+ via MediaStore). */
    fun saveToDownloads(artifact: File): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, artifact.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            return runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    artifact.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            }.getOrNull()
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = File(downloads, artifact.name)
        return runCatching {
            artifact.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            Uri.fromFile(target)
        }.getOrNull()
    }
}
