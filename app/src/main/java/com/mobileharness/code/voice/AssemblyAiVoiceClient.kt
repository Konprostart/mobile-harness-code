package com.mobileharness.code.voice

import org.json.JSONObject

/**
 * AssemblyAI is transcription-only: upload the audio, then poll the transcript job. Both steps are
 * required, so the client hides them behind one call. Auth header is the raw API key (no Bearer).
 */
object AssemblyAiVoiceClient {

    fun validateKey(baseUrl: String, apiKey: String, transport: VoiceTransport = HttpVoiceTransport): Result<Unit> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("API key cannot be blank"))
        return runCatching {
            val resp = transport.get("${baseUrl.trimEnd('/')}/v2/transcript?limit=1", mapOf("Authorization" to key))
            if (resp.isSuccess) Unit
            else throw IllegalStateException(VoiceErrors.httpError(resp.error, resp.code, "AssemblyAI", key))
        }
    }

    fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        transport: VoiceTransport = HttpVoiceTransport,
    ): Result<String> {
        val key = apiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("AssemblyAI API key is missing"))
        val root = baseUrl.trimEnd('/')
        val auth = mapOf("Authorization" to key)
        return runCatching {
            // 1. Upload, which returns a URL to hand to the transcript job.
            val upload = transport.postRaw("$root/v2/upload", auth, audioBytes, "application/octet-stream")
            if (!upload.isSuccess) throw IllegalStateException(VoiceErrors.httpError(upload.error, upload.code, "AssemblyAI", key))
            val uploadUrl = runCatching { JSONObject(upload.text()).optString("upload_url") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("AssemblyAI did not return an upload URL")

            // 2. Submit the transcription job.
            val payload = JSONObject().put("audio_url", uploadUrl)
            if (model.isNotBlank() && model != "universal") payload.put("speech_model", model)
            val job = transport.postJson("$root/v2/transcript", auth, payload.toString())
            if (!job.isSuccess) throw IllegalStateException(VoiceErrors.httpError(job.error, job.code, "AssemblyAI", key))
            val jobId = runCatching { JSONObject(job.text()).optString("id") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("AssemblyAI did not return a transcript id")

            // 3. Poll until the job leaves queued/processing.
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1_500)
                val status = transport.get("$root/v2/transcript/$jobId", auth)
                val obj = runCatching { JSONObject(status.text()) }.getOrNull()
                    ?: throw IllegalStateException("AssemblyAI returned an invalid response")
                when (obj.optString("status")) {
                    "completed" -> return@runCatching obj.optString("text").trim()
                    "error" -> throw IllegalStateException(obj.optString("error", "AssemblyAI transcription failed"))
                }
            }
            throw IllegalStateException("AssemblyAI transcription timed out")
        }
    }
}
