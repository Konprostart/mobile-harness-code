package com.mobileharness.code.network

import java.net.URI

/**
 * Optional, opt-in server synchronization and backup transport.
 *
 * The user chooses between their own real SSH server and a local/Android
 * `dukoi.com` server. Both are strictly optional: nothing is synced unless the
 * user enables a destination, and there is no telemetry anywhere in this path.
 */
enum class ServerSyncMode { SSH, DUKOI }

data class ServerSyncConfig(
    val enabled: Boolean = false,
    val mode: ServerSyncMode = ServerSyncMode.SSH,
    // SSH
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshKeyPath: String = "",
    val sshRemoteDir: String = "",
    // dukoi.com
    val dukoiBaseUrl: String = "",
    val dukoiToken: String = "",
) {
    /** Human-readable problems that block syncing; empty means the config is usable. */
    fun validate(): List<String> {
        if (!enabled) return listOf("Sync is disabled")
        return when (mode) {
            ServerSyncMode.SSH -> buildList {
                if (sshHost.isBlank()) add("SSH host is required")
                if (sshPort !in 1..65535) add("SSH port must be 1–65535")
                if (sshUser.isBlank()) add("SSH user is required")
                if (sshRemoteDir.isBlank()) add("SSH remote directory is required")
            }
            ServerSyncMode.DUKOI -> buildList {
                if (dukoiBaseUrl.isBlank()) add("Server URL is required")
                else if (runCatching { URI(dukoiBaseUrl) }.getOrNull()?.scheme == null) add("Server URL must include a scheme")
            }
        }
    }
}

/** Pure, testable SSH transfer command construction. */
object SshSyncCommands {
    private fun commonOptions(config: ServerSyncConfig): List<String> = buildList {
        add("-P")
        add(config.sshPort.toString())
        if (config.sshKeyPath.isNotBlank()) {
            add("-i")
            add(config.sshKeyPath)
        }
        add("-o")
        add("StrictHostKeyChecking=accept-new")
        add("-o")
        add("BatchMode=yes")
    }

    fun pushArgs(config: ServerSyncConfig, localFile: String, remoteFileName: String): List<String> =
        commonOptions(config) + listOf(
            localFile,
            "${config.sshUser}@${config.sshHost}:${config.sshRemoteDir.trimEnd('/')}/$remoteFileName",
        )

    fun pullArgs(config: ServerSyncConfig, remoteFileName: String, localFile: String): List<String> =
        commonOptions(config) + listOf(
            "${config.sshUser}@${config.sshHost}:${config.sshRemoteDir.trimEnd('/')}/$remoteFileName",
            localFile,
        )
}

/** Builds the dukoi.com sync endpoint for a backup push/pull. */
object DukoiSyncEndpoints {
    fun pushUrl(config: ServerSyncConfig, fileName: String): String =
        "${config.dukoiBaseUrl.trimEnd('/')}/sync?name=${java.net.URLEncoder.encode(fileName, "UTF-8")}"

    fun latestUrl(config: ServerSyncConfig): String =
        "${config.dukoiBaseUrl.trimEnd('/')}/sync/latest"

    fun authorizationHeader(config: ServerSyncConfig): Map<String, String> =
        if (config.dukoiToken.isBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer ${config.dukoiToken}")
}
