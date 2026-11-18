package com.mobileharness.code.network

import com.mobileharness.code.runtime.ReleaseCommandRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface ServerSyncResult {
    data class Success(val message: String) : ServerSyncResult
    data class Failure(val message: String) : ServerSyncResult
}

/**
 * A sync transport reads/writes files at a *path* it can reach. SSH transports see
 * guest paths (the private Linux runtime's view), HTTP transports see host paths.
 */
interface ServerSyncTransport {
    fun push(sourceFilePath: String, remoteName: String): ServerSyncResult
    fun pull(remoteName: String, destinationFilePath: String): ServerSyncResult
}

/** Pushes/pulls backups over the user's own SSH server using the guest `scp` binary. */
class SshSyncTransport(
    private val config: ServerSyncConfig,
    private val runner: ReleaseCommandRunner,
) : ServerSyncTransport {
    override fun push(sourceFilePath: String, remoteName: String): ServerSyncResult {
        val result = runner.run("scp", SshSyncCommands.pushArgs(config, sourceFilePath, remoteName))
        return if (result.succeeded) ServerSyncResult.Success("Backup pushed over SSH")
        else ServerSyncResult.Failure(result.stderr.ifBlank { "scp failed (exit ${result.exitCode})" })
    }

    override fun pull(remoteName: String, destinationFilePath: String): ServerSyncResult {
        val result = runner.run("scp", SshSyncCommands.pullArgs(config, remoteName, destinationFilePath))
        return if (result.succeeded) ServerSyncResult.Success("Backup pulled over SSH")
        else ServerSyncResult.Failure(result.stderr.ifBlank { "scp failed (exit ${result.exitCode})" })
    }
}

/** Pushes/pulls backups to the local/Android `dukoi.com` server over HTTP(S). */
class DukoiSyncTransport(private val config: ServerSyncConfig) : ServerSyncTransport {
    override fun push(sourceFilePath: String, remoteName: String): ServerSyncResult {
        val file = File(sourceFilePath)
        if (!file.isFile || file.length() == 0L) return ServerSyncResult.Failure("Nothing to sync")
        return runCatching {
            val connection = (URL(DukoiSyncEndpoints.pushUrl(config, remoteName)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("User-Agent", "MobileHarnessCode-Android")
                DukoiSyncEndpoints.authorizationHeader(config).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            connection.outputStream.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
            val status = connection.responseCode
            connection.disconnect()
            if (status in 200..299) ServerSyncResult.Success("Backup pushed to server")
            else ServerSyncResult.Failure("Server returned HTTP $status")
        }.getOrElse { ServerSyncResult.Failure(it.message ?: "Server sync failed") }
    }

    override fun pull(remoteName: String, destinationFilePath: String): ServerSyncResult {
        return runCatching {
            val connection = (URL(DukoiSyncEndpoints.latestUrl(config)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "MobileHarnessCode-Android")
                DukoiSyncEndpoints.authorizationHeader(config).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.disconnect()
                return@runCatching ServerSyncResult.Failure("Server returned HTTP $status")
            }
            val destination = File(destinationFilePath).apply { parentFile?.mkdirs() }
            connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            connection.disconnect()
            ServerSyncResult.Success("Backup pulled from server")
        }.getOrElse { ServerSyncResult.Failure(it.message ?: "Server sync failed") }
    }
}

object ServerSyncManager {
    /** Selects the transport for the configured destination. */
    fun transport(config: ServerSyncConfig, sshRunner: ReleaseCommandRunner): ServerSyncTransport = when (config.mode) {
        ServerSyncMode.SSH -> SshSyncTransport(config, sshRunner)
        ServerSyncMode.DUKOI -> DukoiSyncTransport(config)
    }
}
