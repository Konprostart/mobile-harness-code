package com.mobileharness.code.telegram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mobileharness.code.MainActivity
import com.mobileharness.code.R
import com.mobileharness.code.backup.BackupController
import com.mobileharness.code.backup.BackupJobStatus
import com.mobileharness.code.ui.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Foreground service that long-polls the Telegram Bot API and dispatches commands through
 * [TelegramCommandRouter]. It is opt-in (started only when the user enables the bot and
 * provides a token) and uses `START_STICKY` so the poller resumes after a low-memory kill.
 *
 * Two jobs, not one: the poll loop can die on a terminal error (e.g. invalid token) while
 * the support job's collectors — notifications and backup watcher — survive and can be
 * restarted. The token is re-read from [TelegramSettings] on every reconnect so deleting it
 * in Settings stops the poller without ever caching the secret in the service.
 */
class TelegramBotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = TelegramApiClient()
    private val router by lazy { TelegramCommandRouter(this) }
    private var pollingJob: Job? = null
    private var supportJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TelegramSettings(this).setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val settings = TelegramSettings(this)
        if (!settings.enabled || !settings.hasToken()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        startSupport()
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch { pollLoop() }
    }

    private fun startSupport() {
        if (supportJob?.isActive == true) return
        supportJob = scope.launch {
            launch { TelegramNotifications.outbox.collect(::broadcast) }
            launch { watchBackup() }
        }
    }

    private suspend fun pollLoop() {
        val settings = TelegramSettings(this)
        val token = settings.currentToken()
        if (token == null) {
            notifyStopped(R.string.telegram_stopped_no_token)
            stopSelf()
            return
        }

        var botName: String? = null
        try {
            botName = api.getMe(token).username.takeIf(String::isNotBlank)
            updateNotification(botName)
        } catch (error: TelegramApiFailure.InvalidToken) {
            settings.setEnabled(false)
            notifyStopped(R.string.telegram_stopped_invalid_token)
            stopSelf()
            return
        } catch (_: TelegramApiFailure) {
            // Network hiccup at startup: fall through and let the poll loop retry.
        }

        var backoff = INITIAL_BACKOFF_MS
        while (kotlin.coroutines.coroutineContext.isActive && settings.enabled) {
            val current = settings.currentToken()
            if (current == null) {
                notifyStopped(R.string.telegram_stopped_no_token)
                stopSelf()
                return
            }
            val offset = settings.lastUpdateId + 1
            try {
                val updates = api.getUpdates(current, offset)
                for (update in updates) {
                    // Advance the offset BEFORE handling so a crash mid-batch never re-runs
                    // a command after restart (which would double a backup or pairing).
                    settings.lastUpdateId = update.updateId
                    handleUpdate(current, update)
                }
                backoff = INITIAL_BACKOFF_MS
            } catch (_: TelegramApiFailure.InvalidToken) {
                settings.setEnabled(false)
                notifyStopped(R.string.telegram_stopped_invalid_token)
                stopSelf()
                return
            } catch (_: TelegramApiFailure.Conflict) {
                delay(CONFLICT_RETRY_MS)
            } catch (error: TelegramApiFailure.RateLimited) {
                delay(error.retryAfterSeconds.coerceIn(1L, 60L) * 1000L)
            } catch (_: TelegramApiFailure) {
                delay(backoff)
                backoff = min(backoff * 2, MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun handleUpdate(token: String, update: TelegramUpdate) {
        val message = update.message
        if (message != null) {
            val reply = router.route(message)
            sendReply(token, message.chat?.id, reply)
        }
        update.callbackQuery?.let { callback ->
            runCatching { api.answerCallbackQuery(token, callback.id, "") }
        }
    }

    private suspend fun sendReply(token: String, chatId: Long?, reply: TelegramReply) {
        if (chatId == null) return
        when (reply) {
            is TelegramReply.Text -> runCatching {
                api.sendMessage(token, chatId, reply.text.take(MAX_MESSAGE_CHARS), reply.keyboard.toJson())
            }
            is TelegramReply.Document -> {
                runCatching { api.sendDocument(token, chatId, reply.file, reply.caption) }
                reply.file.delete()
            }
            TelegramReply.Ignore -> Unit
        }
    }

    private suspend fun broadcast(message: String) {
        val settings = TelegramSettings(this)
        val token = settings.currentToken() ?: return
        settings.pairedChats.forEach { chatId ->
            runCatching { api.sendMessage(token, chatId, message.take(MAX_MESSAGE_CHARS)) }
        }
    }

    private suspend fun watchBackup() {
        var last: BackupJobStatus? = null
        BackupController.state.collect { state ->
            if (state.status == last) return@collect
            last = state.status
            when (state.status) {
                BackupJobStatus.COMPLETE -> state.summary?.let { summary ->
                    TelegramNotifications.publish(
                        AppStrings.get(this, R.string.telegram_backup_notify_done, summary.projects, summary.files),
                    )
                }
                BackupJobStatus.ERROR -> TelegramNotifications.publish(
                    AppStrings.get(this, R.string.telegram_backup_notify_failed),
                )
                else -> Unit
            }
        }
    }

    // --- foreground / notification ------------------------------------------

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification(null))
        }
    }

    private fun updateNotification(botName: String?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(botName))
    }

    private fun notification(botName: String?): android.app.Notification {
        val title = str(R.string.telegram_notification_title)
        val text = if (botName != null) str(R.string.telegram_notification_running, botName)
        else str(R.string.telegram_notification_connecting)
        val stopIntent = PendingIntent.getService(
            this,
            301,
            Intent(this, TelegramBotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .addAction(0, str(R.string.telegram_notification_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyStopped(id: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(str(R.string.telegram_notification_title))
            .setContentText(str(id))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(STOPPED_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        300,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun str(id: Int, vararg args: Any): String = AppStrings.get(this, id, *args)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.mobileharness.code.telegram.START"
        const val ACTION_STOP = "com.mobileharness.code.telegram.STOP"
        private const val CHANNEL_ID = "telegram"
        private const val NOTIFICATION_ID = 71
        private const val STOPPED_NOTIFICATION_ID = 72
        private const val MAX_MESSAGE_CHARS = 3800
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val CONFLICT_RETRY_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TelegramBotService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TelegramBotService::class.java).setAction(ACTION_STOP))
        }

        fun ensureNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    AppStrings.get(context, R.string.telegram_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
