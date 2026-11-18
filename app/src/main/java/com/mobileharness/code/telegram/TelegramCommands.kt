package com.mobileharness.code.telegram

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class TelegramCommand(val name: String, val args: List<String>) {
    companion object {
        const val MAX_ARGS = 3
        const val MAX_ARG_LENGTH = 200

        /**
         * Parses `/name@BotUsername arg1 arg2`. The bot mention is stripped so commands
         * addressed to this bot work even when other bots share a group (though pairing
         * itself is private-chat-only). Args are length-capped defensively.
         */
        fun parse(text: String?): TelegramCommand? {
            val trimmed = text?.trim() ?: return null
            if (!trimmed.startsWith("/")) return null
            val parts = trimmed.removePrefix("/").split(Regex("\\s+")).filter(String::isNotBlank)
            if (parts.isEmpty()) return null
            val name = parts.first().substringBefore('@').lowercase()
            if (name.isBlank()) return null
            val args = parts.drop(1).take(MAX_ARGS).map { it.take(MAX_ARG_LENGTH) }
            return TelegramCommand(name, args)
        }
    }
}

sealed interface TelegramReply {
    data class Text(
        val text: String,
        val keyboard: TelegramKeyboard? = null,
    ) : TelegramReply

    data class Document(val file: File, val caption: String) : TelegramReply

    data object Ignore : TelegramReply
}

data class TelegramKeyboard(val buttons: List<List<TelegramButton>>)

data class TelegramButton(val label: String, val callbackData: String) {
    val valid: Boolean
        get() = label.isNotBlank() &&
            (callbackData.length <= 64 || callbackData.startsWith("https://"))
}

/** Serializes an inline keyboard into Telegram's `reply_markup` JSON (null when empty). */
fun TelegramKeyboard?.toJson(): String? {
    if (this == null || buttons.isEmpty()) return null
    val rows = JSONArray()
    buttons.forEach { row ->
        val validButtons = row.filter(TelegramButton::valid)
        if (validButtons.isNotEmpty()) {
            val rowJson = JSONArray()
            validButtons.forEach { button ->
                rowJson.put(
                    JSONObject()
                        .put("text", button.label.take(64))
                        .put("callback_data", button.callbackData.take(64)),
                )
            }
            rows.put(rowJson)
        }
    }
    if (rows.length() == 0) return null
    return JSONObject().put("inline_keyboard", rows).toString()
}
