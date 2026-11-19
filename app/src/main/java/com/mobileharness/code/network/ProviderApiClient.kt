package com.mobileharness.code.network

import android.content.Context
import com.mobileharness.code.R
import com.mobileharness.code.model.ProviderProtocol
import com.mobileharness.code.ui.AppStrings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiscoveredModel(val id: String, val displayName: String = id, val isFree: Boolean = false)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

/** One-shot chat completion result used by the in-app Sidekick assistant. */
sealed interface ChatCompletion {
    data class Success(val text: String, val reasoning: String? = null) : ChatCompletion
    data class Failure(
        val message: String,
        val kind: CompletionFailureKind = CompletionFailureKind.OTHER,
    ) : ChatCompletion
}

/** A base64-encoded image block for a multimodal completion (no data-URL prefix). */
data class CompletionImage(val mimeType: String, val base64: String)

/** Parsed completion payload: final answer text plus an optional chain-of-thought excerpt. */
internal data class CompletionPayload(val text: String, val reasoning: String?)

/** Anthropic API version header required by the native Messages API and its gateways. */
internal const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Authentication headers for a provider request. Kept pure and testable so the per-provider
 * contract is explicit: OpenAI-compatible endpoints and OpenRouter take a Bearer token, while
 * Anthropic-family endpoints additionally send `x-api-key` and `anthropic-version` (the headers
 * the native Anthropic Messages API requires).
 */
internal fun buildAuthHeaders(protocol: ProviderProtocol, apiKey: String): List<Pair<String, String>> {
    if (apiKey.isBlank()) return emptyList()
    val headers = mutableListOf("Authorization" to "Bearer $apiKey")
    val anthropicFamily = protocol != ProviderProtocol.OPENROUTER &&
        protocol != ProviderProtocol.OPENAI_CHAT &&
        protocol != ProviderProtocol.OPENAI_RESPONSES
    if (anthropicFamily) {
        headers += "x-api-key" to apiKey
        headers += "anthropic-version" to ANTHROPIC_VERSION
    }
    return headers
}

/**
 * [context] is optional so existing call sites keep compiling; pass the application
 * context to return user-facing messages in the language picked inside the app.
 */
class ProviderApiClient(private val context: Context? = null) {
    private fun text(@androidx.annotation.StringRes id: Int, fallback: String, vararg args: Any): String =
        context?.let { AppStrings.get(it, id, *args) } ?: fallback

    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure(text(R.string.rt_api_enter_base_url, "Enter a base URL first."))
        }

        var authError = false
        var lastMessage = text(
            R.string.rt_api_no_model_list,
            "This provider did not expose a model list. You can enter a custom model name.",
        )
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(baseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            // The saved key is still used for validation and all inference requests.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = chatCapableModels(ModelResponseParser.parse(response.body), endpoint)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = text(
                        R.string.rt_api_empty_model_list,
                        "The provider replied, but its model list was empty or unsupported.",
                    )
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) {
                text(R.string.rt_api_check_saved_key, "Check the saved API key, then try refreshing again.")
            } else {
                lastMessage
            },
            lastProviderMessage,
        )
    }

    /**
     * NVIDIA's /models lists embedding, reranking, image, speech and safety models alongside chat
     * models, but the coding agents can only use /chat/completions. Hide the ones that cannot work.
     */
    private fun chatCapableModels(models: List<DiscoveredModel>, endpoint: String): List<DiscoveredModel> {
        if (!endpoint.contains("integrate.api.nvidia.com")) return models
        val chat = models.filterNot { model ->
            val id = model.id.lowercase()
            NON_CHAT_NVIDIA_MODEL_MARKERS.any { it in id }
        }
        return chat.ifEmpty { models }
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        discoveredModels: List<DiscoveredModel>,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank()) {
            return@withContext ConnectionValidation.Failure(text(R.string.rt_api_required_fields, "Base URL, model, and API key are required."))
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = validationBody(model, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        // Gateways may need to cold-start a model before returning the first token.
        // A ten-second validation timeout produced false "network" failures even
        // though discovery and the endpoint itself were healthy.
        val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 12_000, readTimeoutMs = 45_000)
        when {
            response.code in 200..299 -> ConnectionValidation.Success(text(R.string.rt_api_connection_verified, "Connection verified."))
            response.code == 401 || response.code == 403 -> ConnectionValidation.Failure(
                text(R.string.rt_api_check_key, "Check this API key or select another saved key."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_rejected, "Rejected"),
            )
            response.code == 404 -> ConnectionValidation.Failure(
                text(R.string.rt_api_check_base_url, "Check the Base URL and selected gateway protocol."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_endpoint_error, "Endpoint error"),
            )
            response.code == 400 && response.body.contains("model", ignoreCase = true) ->
                ConnectionValidation.Failure(
                    text(R.string.rt_api_refresh_models, "Refresh the model list or select a different model."),
                    providerErrorMessage(response.body),
                    text(R.string.rt_api_label_model_error, "Model error"),
                )
            response.code == 429 -> ConnectionValidation.Failure(
                text(R.string.rt_api_rate_limited_retry, "Wait a moment, then retry or use another API key."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_rate_limited, "Rate limited"),
            )
            response.code in 500..599 -> ConnectionValidation.Failure(
                text(R.string.rt_api_provider_unavailable, "The provider is temporarily unavailable. Try again shortly."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_provider_error, "Provider error"),
            )
            response.code > 0 -> ConnectionValidation.Failure(
                text(R.string.rt_api_review_settings, "Review the model, protocol, and endpoint settings."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_request_failed, "Request failed"),
            )
            response.error?.contains("timeout", ignoreCase = true) == true ||
                response.error?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure(
                    text(R.string.rt_api_check_connection, "Check your connection and try again."),
                    response.error,
                    text(R.string.rt_api_label_timed_out, "Timed out"),
                )
            else -> ConnectionValidation.Failure(
                text(R.string.rt_api_check_internet, "Check your internet connection and provider settings."),
                response.error,
                text(R.string.rt_api_label_network_error, "Network error"),
            )
        }
    }

    /**
     * One-shot chat completion for the Sidekick assistant. Reuses the same wire formats as the
     * coding agents: Anthropic Messages for ANTHROPIC/ANTHROPIC_GATEWAY/OPENROUTER, chat
     * completions for OPENAI_CHAT, and the Responses API for OPENAI_RESPONSES.
     */
    suspend fun complete(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = COMPLETION_MAX_TOKENS,
        images: List<CompletionImage> = emptyList(),
    ): ChatCompletion = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank()) {
            return@withContext ChatCompletion.Failure(
                text(R.string.rt_api_required_fields, "Base URL, model, and API key are required."),
            )
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = completionBody(model, protocol, systemPrompt, userMessage, maxTokens, images)
        val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 12_000, readTimeoutMs = 60_000)
        when {
            response.code in 200..299 -> {
                val payload = extractCompletion(response.body, protocol)
                ChatCompletion.Success(payload.text, payload.reasoning)
            }
            response.code == 401 || response.code == 403 -> ChatCompletion.Failure(
                text(R.string.rt_api_check_key, "Check this API key or select another saved key."),
                CompletionFailureKind.AUTH,
            )
            response.code == 429 -> ChatCompletion.Failure(
                text(R.string.rt_api_rate_limited_retry, "Wait a moment, then retry or use another API key."),
                CompletionFailureKind.RATE_LIMITED,
            )
            response.code in 500..599 -> ChatCompletion.Failure(
                text(R.string.rt_api_provider_unavailable, "The provider is temporarily unavailable. Try again shortly."),
                CompletionFailureKind.SERVER_ERROR,
            )
            response.code > 0 -> ChatCompletion.Failure(
                text(R.string.rt_api_http_error, "The provider returned HTTP ${response.code}. Check the URL and account access.", response.code),
                classifyCompletionFailure(response.code, response.error),
            )
            else -> ChatCompletion.Failure(
                response.error ?: text(R.string.rt_api_check_internet, "Check your internet connection and provider settings."),
                classifyCompletionFailure(response.code, response.error),
            )
        }
    }

    internal fun completionBody(
        model: String,
        protocol: ProviderProtocol,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = COMPLETION_MAX_TOKENS,
        images: List<CompletionImage> = emptyList(),
    ): String {
        val safeImages = images.filter { it.mimeType.isNotBlank() && it.base64.isNotBlank() }
        return when (protocol) {
            ProviderProtocol.OPENAI_CHAT -> chatCompletionBody(model, systemPrompt, userMessage, maxTokens, safeImages)
            ProviderProtocol.OPENAI_RESPONSES -> responsesBody(model, systemPrompt, userMessage, safeImages)
            else -> anthropicMessagesBody(model, systemPrompt, userMessage, maxTokens, safeImages)
        }
    }

    /** Anthropic Messages: image blocks precede a trailing text block inside `content`. */
    private fun anthropicMessagesBody(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int,
        images: List<CompletionImage>,
    ): String {
        val content: Any = if (images.isEmpty()) {
            userMessage
        } else {
            val blocks = JSONArray()
            for (image in images) {
                blocks.put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", image.mimeType)
                                .put("data", image.base64),
                        ),
                )
            }
            blocks.put(JSONObject().put("type", "text").put("text", userMessage))
            blocks
        }
        return JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", systemPrompt)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()
    }

    /** OpenAI chat completions: text block plus `image_url` blocks carrying data URLs. */
    private fun chatCompletionBody(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int,
        images: List<CompletionImage>,
    ): String {
        val content: Any = if (images.isEmpty()) {
            userMessage
        } else {
            val blocks = JSONArray()
            blocks.put(JSONObject().put("type", "text").put("text", userMessage))
            for (image in images) {
                blocks.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}")),
                )
            }
            blocks
        }
        return JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", content)),
            )
            .toString()
    }

    /** OpenAI Responses: `input_text` plus `input_image` blocks; kept a plain string without images. */
    private fun responsesBody(
        model: String,
        systemPrompt: String,
        userMessage: String,
        images: List<CompletionImage>,
    ): String {
        if (images.isEmpty()) {
            return JSONObject()
                .put("model", model)
                .put("instructions", systemPrompt)
                .put("input", userMessage)
                .toString()
        }
        val input = JSONArray()
        input.put(JSONObject().put("type", "input_text").put("text", userMessage))
        for (image in images) {
            input.put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", "data:${image.mimeType};base64,${image.base64}"),
            )
        }
        return JSONObject()
            .put("model", model)
            .put("instructions", systemPrompt)
            .put("input", input)
            .toString()
    }

    /**
     * Parses a completion response into final text plus an optional reasoning excerpt.
     *
     * - OpenAI chat: `choices[0].message.content` and DeepSeek-style
     *   `choices[0].message.reasoning_content` (falling back to `reasoning`).
     * - OpenAI Responses: text blocks and `reasoning` summary blocks.
     * - Anthropic Messages: `text` blocks and extended `thinking` blocks.
     */
    internal fun extractCompletion(body: String, protocol: ProviderProtocol): CompletionPayload {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return CompletionPayload(body.take(2000), null)
        val payload = when (protocol) {
            ProviderProtocol.OPENAI_CHAT -> extractOpenAiChat(root)
            ProviderProtocol.OPENAI_RESPONSES -> extractOpenAiResponses(root)
            else -> extractAnthropicMessages(root)
        }
        return CompletionPayload(payload.text.ifBlank { body.take(2000) }, payload.reasoning)
    }

    private fun extractOpenAiChat(root: JSONObject): CompletionPayload {
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val text = message?.optString("content").orEmpty()
        val reasoning = message?.optString("reasoning_content").orEmpty()
            .ifBlank { message?.optString("reasoning").orEmpty() }
        return CompletionPayload(text, reasoning.ifBlank { null })
    }

    private fun extractOpenAiResponses(root: JSONObject): CompletionPayload {
        val output = root.optJSONArray("output") ?: return CompletionPayload("", null)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "reasoning" -> {
                    val summary = item.optJSONArray("summary")
                    if (summary != null) {
                        for (j in 0 until summary.length()) {
                            val part = summary.optJSONObject(j) ?: continue
                            if (part.optString("type") == "summary_text") reasoning.append(part.optString("text"))
                        }
                    }
                    if (reasoning.isEmpty()) reasoning.append(item.optString("summary_text"))
                }
                "message" -> {
                    val content = item.optJSONArray("content")
                    if (content != null) {
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            if (part.optString("type") == "output_text") text.append(part.optString("text"))
                        }
                    }
                }
                "output_text" -> text.append(item.optString("text"))
            }
        }
        return CompletionPayload(text.toString(), reasoning.toString().ifBlank { null })
    }

    private fun extractAnthropicMessages(root: JSONObject): CompletionPayload {
        val content = root.optJSONArray("content") ?: return CompletionPayload("", null)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "thinking" -> reasoning.append(block.optString("thinking").ifBlank { block.optString("text") })
                "redacted_thinking" -> reasoning.append(block.optString("data"))
            }
        }
        return CompletionPayload(text.toString(), reasoning.toString().ifBlank { null })
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        if (!LocalNetwork.isPlainHttpAllowed(endpoint)) {
            return HttpResult(0, "", text(R.string.rt_api_http_public, "Plain http:// is only allowed for local network addresses. Use https:// for this server."))
        }
        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                for ((name, value) in buildAuthHeaders(protocol, apiKey)) {
                    setRequestProperty(name, value)
                }
                if (endpoint.startsWith("https://opencode.ai/zen/")) {
                    // OpenCode Zen expects requests to identify the OpenCode client and session.
                    setRequestProperty("User-Agent", "opencode/1.18.20")
                    setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
                }
                if (body != null) doOutput = true
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { reader ->
                buildString {
                    val buffer = CharArray(8 * 1024)
                    while (length < MAX_PROVIDER_RESPONSE_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PROVIDER_RESPONSE_CHARS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                }
            }.orEmpty()
            connection.disconnect()
            HttpResult(code, responseBody)
        }.getOrElse { HttpResult(0, "", it.message ?: text(R.string.rt_api_network_failed, "Network connection failed")) }
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$base/v1/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$base/models")
            else -> listOf("$base/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$base/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "$base/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "$base/responses"
            else -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> text(R.string.rt_api_rate_limit_reached, "The provider rate limit was reached. Wait a moment and try again.")
        in 500..599 -> text(R.string.rt_api_unavailable_http, "The provider is temporarily unavailable (HTTP $code).", code)
        else -> text(R.string.rt_api_http_error, "The provider returned HTTP $code. Check the URL and account access.", code)
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    private companion object {
        // Some OpenAI-compatible providers reject values below three. Eight remains
        // small while working across OpenAI chat, Anthropic-compatible and router APIs.
        const val VALIDATION_MAX_TOKENS = 8
        const val COMPLETION_MAX_TOKENS = 2048
        const val MAX_PROVIDER_RESPONSE_CHARS = 2 * 1024 * 1024
        val NON_CHAT_NVIDIA_MODEL_MARKERS = listOf(
            "embed", "rerank", "retriever", "flux", "sdxl", "stable-diffusion",
            "parakeet", "whisper", "canary", "riva", "tts", "asr", "ocdrnet", "paddleocr",
            "nemoretriever", "clip", "deplot", "kosmos", "cosmos-predict", "guard",
            "reward", "video-detector", "nemotron-parse",
        )
    }
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())
}
