package com.mobileharness.code.telegram

import android.content.Context
import com.mobileharness.code.BuildConfig
import com.mobileharness.code.R
import com.mobileharness.code.backup.BackupManager
import com.mobileharness.code.backup.BackupStores
import com.mobileharness.code.backup.BackupSummary
import com.mobileharness.code.data.ApiKeyVault
import com.mobileharness.code.data.AppPreferences
import com.mobileharness.code.ui.AppStrings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Authorizes and dispatches a Telegram command. The only command allowed before pairing is
 * `/pair`, and pairing itself is restricted to private chats so a group cannot grant every
 * member bot powers. Unpaired chats get a single line — no command list, no status leaks.
 *
 * Commands map to this app's real capabilities: `/status`, `/version`, `/projects`,
 * `/backup <password>` (encrypted local backup, sent as a document when small enough) and
 * `/apk` (the latest signed release link + SHA-256, read from the published update manifest).
 */
class TelegramCommandRouter(context: Context) {
    private val appContext = context.applicationContext
    private val settings = TelegramSettings(appContext)
    private val status = TelegramStatus(appContext)
    private val preferences = AppPreferences(appContext)
    private val vault = ApiKeyVault(appContext)

    private val recentCommands = ConcurrentHashMap<Long, MutableList<Long>>()

    suspend fun route(message: TelegramMessage): TelegramReply {
        val chatId = message.chat?.id ?: return TelegramReply.Ignore
        val command = TelegramCommand.parse(message.text) ?: return TelegramReply.Ignore

        if (command.name == "pair") {
            if (message.chat?.isPrivate != true) return TelegramReply.Text(str(R.string.telegram_pair_private_only))
            if (settings.isPaired(chatId)) return TelegramReply.Text(str(R.string.telegram_already_paired))
            if (!allow(chatId)) return TelegramReply.Text(str(R.string.telegram_rate_limited))
            return handlePair(command, chatId)
        }

        if (!settings.isPaired(chatId)) return TelegramReply.Text(str(R.string.telegram_not_paired))
        if (!allow(chatId)) return TelegramReply.Text(str(R.string.telegram_rate_limited))

        return when (command.name) {
            "start", "help" -> TelegramReply.Text(str(R.string.telegram_help))
            "version" -> TelegramReply.Text(status.appVersion())
            "status" -> TelegramReply.Text(status.deviceStatus())
            "projects" -> TelegramReply.Text(status.projectsSummary())
            "backup" -> handleBackup(command)
            "apk" -> handleApk()
            else -> TelegramReply.Text(str(R.string.telegram_unknown_command, command.name))
        }
    }

    private fun handlePair(command: TelegramCommand, chatId: Long): TelegramReply {
        val code = command.args.firstOrNull()
        if (code.isNullOrBlank()) {
            val issued = settings.generatePairingCode()
            return TelegramReply.Text(str(R.string.telegram_pair_code_issued, issued))
        }
        return when (settings.redeemPairingCode(code, chatId)) {
            PairingResult.Paired -> TelegramReply.Text(str(R.string.telegram_pair_success))
            PairingResult.NoActiveCode -> TelegramReply.Text(str(R.string.telegram_pair_no_code))
            PairingResult.Expired -> TelegramReply.Text(str(R.string.telegram_pair_expired))
            PairingResult.RateLimited -> TelegramReply.Text(str(R.string.telegram_pair_wrong))
        }
    }

    private suspend fun handleBackup(command: TelegramCommand): TelegramReply {
        val password = command.args.firstOrNull()?.takeIf(String::isNotBlank)
            ?: return TelegramReply.Text(str(R.string.telegram_backup_usage))
        return withContext(Dispatchers.IO) {
            val output = File(appContext.filesDir, "backups/telegram-${System.currentTimeMillis()}.mhbackup")
                .also { it.parentFile?.mkdirs() }
            val summary = runCatching {
                createBackup(output, password)
            }.getOrNull()
            if (summary == null || !output.isFile) {
                output.delete()
                TelegramReply.Text(str(R.string.telegram_backup_failed))
            } else if (output.length() > MAX_UPLOAD_BYTES) {
                output.delete()
                TelegramReply.Text(
                    str(
                        R.string.telegram_backup_too_large,
                        formatBytes(output.length()),
                    ),
                )
            } else {
                TelegramReply.Document(
                    file = output,
                    caption = str(R.string.telegram_backup_done, summary.projects, summary.files),
                )
            }
        }
    }

    private fun createBackup(output: File, password: String): BackupSummary = BackupManager(
        filesDir = appContext.filesDir,
        stores = object : BackupStores {
            override fun exportPrefs() = preferences.exportAll()
            override fun importPrefs(prefs: JSONObject) = Unit
            override fun exportSecrets() = vault.exportAll()
            override fun importSecrets(secrets: JSONObject) = Unit
        },
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
    ).createBackup(output.outputStream().buffered(), password.toCharArray())

    private suspend fun handleApk(): TelegramReply = withContext(Dispatchers.IO) {
        val info = fetchLatestRelease()
        if (info == null) {
            TelegramReply.Text(str(R.string.telegram_apk_unavailable))
        } else {
            TelegramReply.Text(
                str(R.string.telegram_apk_info, info.versionName, info.sha256, info.url),
            )
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? = runCatching {
        val connection = (URL(BuildConfig.APP_UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val root = JSONObject(text)
        val artifact = root.optJSONObject("artifacts")?.optJSONObject("online") ?: return@runCatching null
        ReleaseInfo(
            versionName = root.optString("versionName"),
            sha256 = artifact.optString("sha256"),
            url = artifact.optString("url"),
        )
    }.getOrNull()

    private data class ReleaseInfo(val versionName: String, val sha256: String, val url: String)

    /** Per-chat rate limiting: 12 commands per rolling 60s window, bounded tracked chats. */
    private fun allow(chatId: Long): Boolean {
        if (recentCommands.size > MAX_TRACKED_CHATS) sweep()
        val stamps = recentCommands.getOrPut(chatId) { mutableListOf() }
        synchronized(stamps) {
            val now = System.currentTimeMillis()
            stamps.removeAll { now - it > RATE_WINDOW_MS }
            if (stamps.size >= MAX_COMMANDS_PER_WINDOW) return false
            stamps.add(now)
        }
        return true
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        val iterator = recentCommands.entries.iterator()
        while (iterator.hasNext()) {
            val stamps = iterator.next().value
            synchronized(stamps) {
                stamps.removeAll { now - it > RATE_WINDOW_MS }
                if (stamps.isEmpty()) iterator.remove()
            }
        }
    }

    private fun formatBytes(bytes: Long): String =
        android.text.format.Formatter.formatShortFileSize(appContext, bytes)

    private fun str(id: Int, vararg args: Any): String = AppStrings.get(appContext, id, *args)

    private companion object {
        const val RATE_WINDOW_MS = 60_000L
        const val MAX_COMMANDS_PER_WINDOW = 12
        const val MAX_TRACKED_CHATS = 64
        const val MAX_UPLOAD_BYTES = 48L * 1024 * 1024
    }
}
