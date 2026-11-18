package com.mobileharness.code.telegram

import android.content.Context
import com.mobileharness.code.data.ApiKeyVault
import java.security.MessageDigest
import java.security.SecureRandom

sealed interface PairingResult {
    data object Paired : PairingResult
    data object NoActiveCode : PairingResult
    data object Expired : PairingResult
    data object RateLimited : PairingResult
}

/** Bot tokens are `<botId>:<35-char secret>` (digits, letters, `-`, `_`). */
val TELEGRAM_TOKEN_FORMAT = Regex("^[0-9]{5,12}:[A-Za-z0-9_-]{30,50}$")

/**
 * Telegram bot configuration and pairing state. The bot token is stored encrypted
 * through the shared [ApiKeyVault] (Android Keystore AES-GCM), exactly like provider
 * keys, so a Telegram token never appears in plain SharedPreferences and rides along
 * with password-protected backups.
 *
 * Pairing follows a 6-digit single-use code with a 10-minute window and a 5-attempt
 * limit, compared in constant time so a wrong guess leaks no timing information.
 */
class TelegramSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("telegram_prefs", Context.MODE_PRIVATE)
    private val vault = ApiKeyVault(context)

    val enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).commit()
    }

    fun hasToken(): Boolean = vault.contains(TOKEN_PROVIDER)

    fun botId(): String? = preferences.getString(KEY_BOT_ID, null)

    /** Saves and validates a raw bot token; clears the update offset if the bot changed. */
    fun saveToken(raw: String): Boolean {
        val token = raw.trim()
        if (!TELEGRAM_TOKEN_FORMAT.matches(token)) return false
        val newBotId = token.substringBefore(':')
        val previous = botId()
        vault.put(TOKEN_PROVIDER, token)
        preferences.edit()
            .putString(KEY_BOT_ID, newBotId)
            .apply()
        if (previous != null && previous != newBotId) {
            lastUpdateId = 0L
        }
        return true
    }

    fun currentToken(): String? = vault.get(TOKEN_PROVIDER)

    fun clearToken() {
        vault.remove(TOKEN_PROVIDER)
        preferences.edit().remove(KEY_BOT_ID).apply()
        lastUpdateId = 0L
    }

    var lastUpdateId: Long
        get() = preferences.getLong(KEY_LAST_UPDATE_ID, 0L)
        set(value) { preferences.edit().putLong(KEY_LAST_UPDATE_ID, value).commit() }

    val pairedChats: Set<Long>
        get() = preferences.getString(KEY_PAIRED_CHATS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun isPaired(chatId: Long): Boolean = chatId in pairedChats

    private fun addPairedChat(chatId: Long) {
        val updated = (pairedChats + chatId).sorted()
        preferences.edit().putString(KEY_PAIRED_CHATS, updated.joinToString(",")).commit()
    }

    /** Issues a new 6-digit pairing code; any previous code becomes invalid. */
    fun generatePairingCode(): String {
        val code = (SecureRandom().nextInt(1_000_000)).toString().padStart(6, '0')
        preferences.edit()
            .putString(KEY_PAIRING_CODE, code)
            .putLong(KEY_PAIRING_EXPIRY, System.currentTimeMillis() + PAIRING_WINDOW_MS)
            .putInt(KEY_PAIRING_ATTEMPTS, 0)
            .commit()
        return code
    }

    fun activePairingCode(): String? {
        val code = preferences.getString(KEY_PAIRING_CODE, null) ?: return null
        if (System.currentTimeMillis() > preferences.getLong(KEY_PAIRING_EXPIRY, 0L)) {
            clearPairingCode()
            return null
        }
        return code
    }

    fun redeemPairingCode(candidate: String, chatId: Long): PairingResult {
        val stored = activePairingCode() ?: return PairingResult.NoActiveCode
        val normalized = candidate.trim()
        val valid = normalized.length == stored.length &&
            MessageDigest.isEqual(
                normalized.toByteArray(Charsets.UTF_8),
                stored.toByteArray(Charsets.UTF_8),
            )
        if (!valid) {
            val attempts = preferences.getInt(KEY_PAIRING_ATTEMPTS, 0) + 1
            if (attempts >= PAIRING_MAX_ATTEMPTS) {
                clearPairingCode()
                return PairingResult.Expired
            }
            preferences.edit().putInt(KEY_PAIRING_ATTEMPTS, attempts).commit()
            return PairingResult.RateLimited
        }
        clearPairingCode()
        addPairedChat(chatId)
        return PairingResult.Paired
    }

    private fun clearPairingCode() {
        preferences.edit()
            .remove(KEY_PAIRING_CODE)
            .remove(KEY_PAIRING_EXPIRY)
            .remove(KEY_PAIRING_ATTEMPTS)
            .commit()
    }

    private companion object {
        const val TOKEN_PROVIDER = "telegram_bot"
        const val KEY_ENABLED = "enabled"
        const val KEY_BOT_ID = "bot_id"
        const val KEY_LAST_UPDATE_ID = "last_update_id"
        const val KEY_PAIRED_CHATS = "paired_chats"
        const val KEY_PAIRING_CODE = "pairing_code"
        const val KEY_PAIRING_EXPIRY = "pairing_expiry"
        const val KEY_PAIRING_ATTEMPTS = "pairing_attempts"
        const val PAIRING_WINDOW_MS = 10 * 60 * 1_000L
        const val PAIRING_MAX_ATTEMPTS = 5
    }
}
