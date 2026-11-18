package com.mobileharness.code.telegram

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Telegram Bot API client (long polling). Implemented directly over
 * `HttpURLConnection` + the platform `org.json` so the harness adds no new
 * network dependency: the same stack is used by the provider and GitHub clients.
 *
 * Only four calls are needed for a polling bot: `getMe`, `getUpdates`,
 * `sendMessage` and `sendDocument` (plus `answerCallbackQuery` for inline buttons).
 */
class TelegramApiClient {
    suspend fun getMe(token: String): TelegramBotInfo = withIo {
        val json = request(token, "getMe", emptyMap())
        val result = json.getJSONObject("result")
        TelegramBotInfo(
            id = result.getLong("id"),
            username = result.optString("username"),
        )
    }

    suspend fun getUpdates(
        token: String,
        offset: Long,
        timeoutSeconds: Int = POLL_TIMEOUT_SECONDS,
        limit: Int = 20,
    ): List<TelegramUpdate> = withIo {
        val json = request(
            token,
            "getUpdates",
            buildMap {
                put("offset", offset.toString())
                put("timeout", timeoutSeconds.toString())
                put("limit", limit.toString())
                put("allowed_updates", "[\"message\",\"callback_query\"]")
            },
            readTimeoutMs = (timeoutSeconds + 20) * 1000,
        )
        val array = json.optJSONArray("result") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            runCatching { parseUpdate(array.getJSONObject(index)) }.getOrNull()
        }
    }

    suspend fun sendMessage(token: String, chatId: Long, text: String, replyMarkup: String? = null): Boolean =
        withIo {
            request(
                token,
                "sendMessage",
                buildMap {
                    put("chat_id", chatId.toString())
                    put("text", text)
                    replyMarkup?.let { put("reply_markup", it) }
                },
            )
            true
        }

    /**
     * Uploads a document via multipart/form-data. The Bot API accepts up to 50 MB;
     * callers should guard against larger files before invoking this.
     */
    suspend fun sendDocument(token: String, chatId: Long, file: File, caption: String): Boolean = withIo {
        val boundary = "mhc" + System.currentTimeMillis() + kotlin.random.Random.nextInt(100_000)
        val connection = (URL("$API_BASE/bot$token/sendDocument").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.use { out ->
            fun writePart(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(value.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray(Charsets.UTF_8))
            }
            writePart("chat_id", chatId.toString())
            writePart("caption", caption)
            out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
        readResponse(connection)
        true
    }

    suspend fun answerCallbackQuery(token: String, callbackQueryId: String, text: String): Boolean = withIo {
        request(
            token,
            "answerCallbackQuery",
            buildMap {
                put("callback_query_id", callbackQueryId)
                put("text", text)
            },
        )
        true
    }

    // --- wire model ---------------------------------------------------------

    private fun parseUpdate(json: JSONObject): TelegramUpdate = TelegramUpdate(
        updateId = json.getLong("update_id"),
        message = json.optJSONObject("message")?.let(::parseMessage),
        callbackQuery = json.optJSONObject("callback_query")?.let { callback ->
            TelegramCallbackQuery(
                id = callback.getString("id"),
                from = callback.optJSONObject("from")?.let(::parseUser),
                message = callback.optJSONObject("message")?.let(::parseMessage),
                data = callback.optString("data").takeIf(String::isNotBlank),
            )
        },
    )

    private fun parseMessage(json: JSONObject): TelegramMessage = TelegramMessage(
        messageId = json.getLong("message_id"),
        chat = json.optJSONObject("chat")?.let(::parseChat),
        from = json.optJSONObject("from")?.let(::parseUser),
        date = json.optLong("date"),
        text = json.optString("text").takeIf(String::isNotBlank),
    )

    private fun parseChat(json: JSONObject): TelegramChat = TelegramChat(
        id = json.getLong("id"),
        type = json.optString("type"),
        title = json.optString("title").takeIf(String::isNotBlank),
        username = json.optString("username").takeIf(String::isNotBlank),
    )

    private fun parseUser(json: JSONObject): TelegramUser = TelegramUser(
        id = json.getLong("id"),
        username = json.optString("username").takeIf(String::isNotBlank),
    )

    // --- HTTP -----------------------------------------------------------------

    private suspend fun <T> withIo(block: suspend () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        block()
    }

    private fun request(
        token: String,
        method: String,
        params: Map<String, String>,
        readTimeoutMs: Int = 30_000,
    ): JSONObject {
        val connection = (URL("$API_BASE/bot$token/$method").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        val body = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val text = runCatching {
            (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrElse { throw TelegramApiFailure.Network("Network connection failed") }
        connection.disconnect()

        if (text.isBlank()) {
            if (status in 200..299) return JSONObject()
            throw TelegramApiFailure.Server(status, "empty response")
        }

        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw TelegramApiFailure.Server(status, text.take(200))

        val ok = json.optBoolean("ok", status in 200..299)
        if (ok) return json

        val description = json.optString("description", "HTTP $status")
        val errorCode = json.optInt("error_code", status)
        val retryAfter = json.optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L
        throw when (errorCode) {
            401 -> TelegramApiFailure.InvalidToken(description)
            409 -> TelegramApiFailure.Conflict(description)
            429 -> TelegramApiFailure.RateLimited(description, retryAfter)
            else -> TelegramApiFailure.Server(status, description)
        }
    }

    private companion object {
        const val API_BASE = "https://api.telegram.org"
        const val POLL_TIMEOUT_SECONDS = 25
    }
}

data class TelegramBotInfo(val id: Long, val username: String)

data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage?,
    val callbackQuery: TelegramCallbackQuery?,
)

data class TelegramMessage(
    val messageId: Long,
    val chat: TelegramChat?,
    val from: TelegramUser?,
    val date: Long,
    val text: String?,
)

data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String?,
    val username: String?,
) {
    val isPrivate: Boolean get() = type == "private"
}

data class TelegramUser(val id: Long, val username: String?)

data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser?,
    val message: TelegramMessage?,
    val data: String?,
)

sealed class TelegramApiFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidToken(description: String) : TelegramApiFailure("Invalid token: $description")
    class Conflict(description: String) : TelegramApiFailure("Conflict: $description")
    class RateLimited(description: String, val retryAfterSeconds: Long) :
        TelegramApiFailure("Rate limited: $description")

    class Network(message: String) : TelegramApiFailure(message)
    class Server(status: Int, description: String) : TelegramApiFailure("HTTP $status: $description")
}
