package com.mobileharness.code.voice

import org.json.JSONObject

/** Shared error helpers so no provider echoes an API key into a message or log. */
object VoiceErrors {
    fun redact(message: String?, apiKey: String): String {
        val msg = message ?: return "unknown error"
        val key = apiKey.trim()
        if (key.isBlank() || !msg.contains(key)) return msg
        return msg.replace(key, "[redacted]")
    }

    /** Builds a human error from a non-2xx response body, without exposing the key. */
    fun httpError(body: String?, code: Int, name: String, apiKey: String = ""): String {
        val detail = runCatching {
            val o = JSONObject(body ?: "")
            o.optJSONObject("error")?.optString("message")
                ?: o.optString("message")
                ?: o.optString("error")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val message = detail ?: "$name request failed (HTTP $code)"
        return redact(message, apiKey)
    }
}
