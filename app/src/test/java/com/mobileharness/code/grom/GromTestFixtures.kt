package com.mobileharness.code.grom

import app.grom.sdk.connect.GromHttpRequest
import app.grom.sdk.connect.GromHttpResponse
import app.grom.sdk.connect.GromTransport
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class MemoryConfigStore(initial: GromConfig = GromConfig()) : GromConfigStore {
    var current: GromConfig = initial
    override fun load(): GromConfig = current
    override fun save(config: GromConfig) { current = config }
}

class MemorySecretStore : GromSecretStore {
    val values = mutableMapOf<String, String>()
    override fun put(id: String, secret: String) { values[id] = secret }
    override fun get(id: String): String? = values[id]
    override fun remove(id: String) { values.remove(id) }
}

data class GromServiceDoc(
    val id: String,
    val title: String,
    val description: String = "",
    val capabilities: List<String> = emptyList(),
    val scopedOps: List<String> = emptyList(),
)

/**
 * A tiny, deterministic GROM data-plane double implementing the S6 routes the
 * client uses: `/grom/v1/connect`, `/v1/auth/login`, `/v1/plugins`,
 * `/v1/plugins/{id}/rpc`. It is shared by the in-memory transport test and the
 * real-HTTP integration test so both exercise the exact same wire behaviour.
 */
class FakeGromDataPlane(
    var gromVersion: String = "0.16.0-server29",
    var apiVersion: Int = 1,
    var authMethods: List<String> = listOf("password", "key"),
    var scopes: Set<String> = setOf("services.read", "services.call", "status.read"),
    var services: List<GromServiceDoc> = listOf(
        GromServiceDoc(
            id = "agent",
            title = "Agent",
            description = "supervised KV agent",
            capabilities = listOf("echo", "health", "kv.get", "kv.put", "ping", "status"),
            scopedOps = listOf("echo", "kv.get", "kv.put", "ping"),
        ),
        GromServiceDoc(
            id = "llm",
            title = "LLM Proxy",
            description = "OpenAI/Anthropic proxy",
            capabilities = listOf("llm.proxy", "chat.completions", "messages"),
            scopedOps = listOf("chat.completions", "messages"),
        ),
    ),
    var rejectLogin: Boolean = false,
) {
    fun handle(method: String, path: String, headers: Map<String, String>, body: String): Pair<Int, String> =
        when {
            path == "/grom/v1/connect" && method == "GET" ->
                Pair(200, connectDocument(authenticated = isAuthenticated(headers)))

            path == "/v1/auth/login" && method == "POST" ->
                if (rejectLogin) Pair(401, errorBody("UNAUTHORIZED", "bad password"))
                else Pair(200, JSONObject().apply {
                    put("ok", true)
                    put("token", "session-token-1")
                    put("expiresAtMs", System.currentTimeMillis() + 3_600_000L)
                }.toString())

            path == "/v1/plugins" && method == "GET" -> Pair(200, pluginsDocument())

            path.startsWith("/v1/plugins/") && path.endsWith("/rpc") && method == "POST" -> {
                val serviceId = path.removePrefix("/v1/plugins/").removeSuffix("/rpc")
                val service = services.firstOrNull { it.id == serviceId }
                if (service == null) Pair(404, errorBody("NOT_FOUND", "no such service"))
                else {
                    val op = runCatching { JSONObject(body).optString("op") }.getOrDefault("")
                    Pair(200, JSONObject().apply {
                        put("ok", true)
                        put("plugin", serviceId)
                        put("op", op)
                        put("result", JSONObject().put("stored", true).put("echo", op))
                    }.toString())
                }
            }

            else -> Pair(404, errorBody("NOT_FOUND", "no route"))
        }

    private fun isAuthenticated(headers: Map<String, String>): Boolean =
        headers.any { (key, value) ->
            key.equals("authorization", ignoreCase = true) && value.startsWith("Bearer ") ||
                key.equals("x-grom-key", ignoreCase = true) && value.isNotBlank()
        }

    private fun connectDocument(authenticated: Boolean): String = JSONObject().apply {
        put("ok", true)
        put("schema", "grom-connect-v1")
        put("service", "grom-super")
        put("grom", gromVersion)
        put("api", apiVersion)
        put("compat", JSONObject().put("minApi", apiVersion).put("maxApi", apiVersion))
        put("state", "ready")
        put("timeMs", System.currentTimeMillis())
        put("dataPlane", JSONObject().put("enabled", true).put("running", true).put("bind", "LOOPBACK").put("port", 47002))
        put("auth", JSONObject().put("methods", JSONArray(authMethods)).put("login", "/v1/auth/login"))
        if (authenticated) {
            put("principal", JSONObject().put("kind", "peer"))
            put("capabilities", JSONArray(scopes.toList()))
            put("services", JSONObject().apply {
                put("listed", true)
                put("path", "/v1/plugins")
                put("requires", "services.read")
                put("items", JSONArray(services.map { serviceDoc(it) }))
            })
        } else {
            put("principal", JSONObject().put("kind", "anonymous"))
            put("capabilities", JSONArray())
            put("services", JSONObject().apply {
                put("listed", false)
                put("path", "/v1/plugins")
                put("requires", "services.read")
                put("items", JSONArray())
            })
        }
    }.toString()

    private fun pluginsDocument(): String = JSONObject().apply {
        put("ok", true)
        put("plugins", JSONArray(services.map { serviceDoc(it) }))
    }.toString()

    private fun serviceDoc(service: GromServiceDoc): JSONObject = JSONObject().apply {
        put("id", service.id)
        put("title", service.title)
        put("description", service.description)
        put("capabilities", JSONArray(service.capabilities))
        put("scopedOps", JSONArray(service.scopedOps))
    }

    private fun errorBody(code: String, message: String): String =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message)).toString()
}

/** Routes [FakeGromDataPlane] responses through the SDK's in-memory transport seam. */
class FakeGromTransport(private val plane: FakeGromDataPlane) : GromTransport {
    val requests = mutableListOf<GromHttpRequest>()
    override fun execute(request: GromHttpRequest): GromHttpResponse {
        requests += request
        val path = runCatching { URI(request.url).path }.getOrDefault(request.url)
        val (status, body) = plane.handle(request.method, path, request.headers, request.body)
        return GromHttpResponse(status, body)
    }
}
