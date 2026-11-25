// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk.connect

import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.util.Base64

/**
 * A thin client for Grom's public data plane (D93).
 *
 * This is the supported way for another application to use Grom as a local
 * server: it speaks the existing HTTP/JSON API, it adds no protocol, and it
 * carries no networking dependency beyond `HttpURLConnection`. A host that
 * already has a client — `tools/grom-fleet`, the hub, a Kotlin app — can use the
 * same routes directly; this class exists so a host does not have to re-derive
 * the signature format, the nonce rules and the error envelope.
 *
 * The client never owns a private key. A caller that uses Ed25519 supplies a
 * [GromSigner]; the key material stays in the host's own Keystore-backed store.
 * That is deliberate (S1-R004, D15, D72): a shared library is not a key store.
 *
 * Nothing here decides what the caller may do. The server grants scopes and
 * refuses everything else with `403 SCOPE_REQUIRED`; the client only reports it.
 */
class GromConnectClient(
    baseUrl: String,
    private val transport: GromTransport = UrlConnectionTransport(),
    /** Unix seconds, used for the signed `x-grom-timestamp` header. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** 8–128 chars of `[A-Za-z0-9_-]`; one per signed request (S5-R018). */
    private val nonceFactory: () -> String = { randomNonce() }
) {

    /** `scheme://host:port`, with no path, query or credentials. */
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    // ------------------------------------------------------------------ calls

    /**
     * `GET /grom/v1/connect` (D91).
     *
     * Without a credential the document describes how to authenticate; with one
     * it adds the caller's capabilities and the service list the caller may
     * read. An invalid credential is refused rather than downgraded to the
     * anonymous answer, so a host never mistakes a rejected key for "no
     * services configured".
     */
    fun connect(credential: GromCredential? = null): GromConnect {
        val response = call("GET", CONNECT_PATH, "", credential)
        val body = decodeObject(response, "the connect document")
        return GromConnect.from(body)
    }

    /** `POST /v1/auth/login`: exchanges a password for a short-lived session. */
    fun login(password: String): GromSession {
        require(password.isNotEmpty()) { "password is empty" }
        val response = call(
            "POST",
            LOGIN_PATH,
            GromJson.encode(mapOf("password" to password)),
            credential = null
        )
        val body = decodeObject(response, "the login answer")
        val token = body["token"] as? String
        if (token.isNullOrEmpty()) {
            throw GromConnectException(
                response.status,
                "BAD_RESPONSE",
                "the login answer carried no token"
            )
        }
        val expires = (body["expiresAtMs"] as? Number)?.toLong() ?: 0L
        return GromSession(token, expires)
    }

    /** `GET /v1/status`: the measured status the credential is allowed to read. */
    fun status(credential: GromCredential): Map<String, Any?> =
        decodeObject(call("GET", STATUS_PATH, "", credential), "the status answer")

    /**
     * `GET /v1/plugins`: services, descriptions, capabilities and the
     * operations each service exposes to a scoped caller (`services.read`).
     */
    fun services(credential: GromCredential): List<GromService> {
        val body = decodeObject(call("GET", PLUGINS_PATH, "", credential), "the service list")
        return GromService.listFrom(body["plugins"])
    }

    /**
     * `POST /v1/plugins/{id}/rpc`.
     *
     * The op must be listed in the service's `scopedOps` for a scoped
     * credential; the server enforces that, and this method refuses to build a
     * request that could not be signed correctly (an id or op that is not a
     * simple token would change the signed path).
     */
    fun rpc(
        credential: GromCredential,
        service: String,
        op: String,
        args: Map<String, Any?> = emptyMap()
    ): GromRpcResult {
        require(SERVICE_ID.matches(service)) { "invalid service id: $service" }
        require(OP_PATTERN.matches(op)) { "invalid op: $op" }
        val path = "$PLUGINS_PATH/$service/rpc"
        val body = GromJson.encode(linkedMapOf("op" to op, "args" to args))
        val answer = decodeObject(call("POST", path, body, credential), "the rpc answer")
        @Suppress("UNCHECKED_CAST")
        val result = answer["result"] as? Map<String, Any?> ?: emptyMap()
        return GromRpcResult(
            plugin = answer["plugin"] as? String ?: service,
            op = answer["op"] as? String ?: op,
            result = result
        )
    }

    /** The session token as a ready-to-use credential. */
    fun session(token: String): GromCredential = GromCredential.Session(token)

    // --------------------------------------------------------------- transport

    private fun call(
        method: String,
        path: String,
        body: String,
        credential: GromCredential?
    ): GromHttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (body.isNotEmpty()) {
            headers["Content-Type"] = "application/json; charset=utf-8"
        }
        when (credential) {
            null -> Unit
            is GromCredential.Session -> headers["Authorization"] = "Bearer ${credential.token}"
            is GromCredential.Key -> headers.putAll(signedHeaders(credential.signer, method, path, body))
        }
        val request = GromHttpRequest(method, baseUrl + path, headers, body)
        return try {
            transport.execute(request)
        } catch (failure: GromConnectException) {
            throw failure
        } catch (failure: Exception) {
            // The URL and the exception message may carry an address the host
            // should not surface; the code is the contract.
            throw GromConnectException(0, "TRANSPORT_FAILED", "the server could not be reached")
        }
    }

    /**
     * The four headers a signed request carries. The signed string is produced
     * by [canonicalString], which `GromConnectClientTest` proves byte-for-byte
     * identical to the server's `RequestSigner.canonicalString`.
     */
    private fun signedHeaders(
        signer: GromSigner,
        method: String,
        path: String,
        body: String
    ): Map<String, String> {
        require(signer.keyId.isNotEmpty()) { "the signer has no key id" }
        val timestamp = clock().toString()
        val nonce = nonceFactory()
        require(NONCE.matches(nonce)) { "the nonce is not in the accepted alphabet" }
        val canonical = canonicalString(method, path, body, timestamp, nonce)
        val signature = signer.sign(canonical.toByteArray(Charsets.UTF_8))
        require(signature.size == SIGNATURE_BYTES) { "Ed25519 signatures are 64 bytes" }
        return linkedMapOf(
            HEADER_KEY to signer.keyId,
            HEADER_TIMESTAMP to timestamp,
            HEADER_NONCE to nonce,
            HEADER_SIGNATURE to Base64.getEncoder().encodeToString(signature)
        )
    }

    private fun decodeObject(response: GromHttpResponse, what: String): Map<String, Any?> {
        if (response.status !in 200..299) throw failure(response)
        return try {
            GromJson.parseObject(response.body)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw GromConnectException(
            response.status,
            "BAD_RESPONSE",
            "$what was not a JSON object"
        )
    }

    private fun failure(response: GromHttpResponse): GromConnectException {
        val parsed = try {
            GromJson.parseObject(response.body)
        } catch (_: IllegalArgumentException) {
            null
        }
        val error = parsed?.get("error") as? Map<*, *>
        val code = (error?.get("code") as? String).orEmpty()
        val message = (error?.get("message") as? String).orEmpty()
        val effective = when {
            code.isNotEmpty() -> code
            response.status in 300..399 -> "REDIRECT_NOT_FOLLOWED"
            else -> "BAD_RESPONSE"
        }
        return GromConnectException(
            response.status,
            effective,
            message.ifEmpty { "the server answered HTTP ${response.status}" }
        )
    }

    companion object {

        const val API_VERSION: Int = 1
        const val CONNECT_SCHEMA: String = "grom-connect-v1"

        const val CONNECT_PATH: String = "/grom/v1/connect"
        const val LOGIN_PATH: String = "/v1/auth/login"
        const val STATUS_PATH: String = "/v1/status"
        const val PLUGINS_PATH: String = "/v1/plugins"

        const val HEADER_KEY: String = "x-grom-key"
        const val HEADER_TIMESTAMP: String = "x-grom-timestamp"
        const val HEADER_NONCE: String = "x-grom-nonce"
        const val HEADER_SIGNATURE: String = "x-grom-signature"

        private const val SIGNATURE_BYTES: Int = 64
        private val NONCE: Regex = Regex("[A-Za-z0-9_-]{8,128}")
        private val SERVICE_ID: Regex = Regex("^[a-z][a-z0-9-]{1,31}$")
        private val OP_PATTERN: Regex = Regex("^[a-z][a-z0-9._-]{0,63}$")

        /**
         * The exact string the server verifies:
         * `grom-ed25519-v1\nMETHOD\npath\ntimestamp\nnonce\nsha256hex(body)`.
         * Public so the engine test can compare it with `RequestSigner`; the two
         * must never drift into two incompatible formats.
         */
        fun canonicalString(
            method: String,
            path: String,
            body: String,
            timestamp: String,
            nonce: String
        ): String = buildString {
            append("grom-ed25519-v1").append('\n')
            append(method.uppercase()).append('\n')
            append(path).append('\n')
            append(timestamp).append('\n')
            append(nonce).append('\n')
            append(sha256Hex(body.toByteArray(Charsets.UTF_8)))
        }

        /** 22 characters of URL-safe base64; the server accepts `[A-Za-z0-9_-]{8,128}`. */
        fun randomNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
            }
            return out.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * Accepts `http://host:port` and `https://host:port` only. A base URL
         * with a path would silently change every signed string, so it is
         * refused instead of normalised behind the caller's back; so are
         * credentials in the URL and a query or fragment.
         */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "the server URL is empty" }
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                throw IllegalArgumentException("the server URL is not a valid URL")
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") {
                "the server URL must be http or https"
            }
            require(uri.userInfo == null) { "the server URL must not carry credentials" }
            require(uri.query == null && uri.fragment == null) {
                "the server URL must not carry a query or fragment"
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") {
                "the server URL must be scheme://host:port with no path"
            }
            val host = uri.host ?: throw IllegalArgumentException("the server URL has no host")
            val port = if (uri.port == -1) {
                if (scheme == "https") 443 else 80
            } else {
                uri.port
            }
            require(port in 1..65535) { "the server port is out of range" }
            require(!host.contains('@') && !host.contains('/')) {
                "the server host is malformed"
            }
            return "$scheme://$host:$port"
        }
    }
}

// ---------------------------------------------------------------- public types

/** A request the client hands to a [GromTransport]. */
data class GromHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

/** A response a [GromTransport] returns; the body is text, never a stream. */
data class GromHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

/**
 * The one seam a host replaces to use its own networking stack (or a test
 * double). It must not follow redirects: the signed path would change.
 */
fun interface GromTransport {
    fun execute(request: GromHttpRequest): GromHttpResponse
}

/**
 * The host's Ed25519 identity. The SDK never generates, stores or exports a
 * private key (D72, D93): it only asks the host to sign one canonical string.
 */
interface GromSigner {
    /** 16 lowercase hex characters, derived from the public key by the host. */
    val keyId: String

    /** A 64-byte Ed25519 signature over [message]. */
    fun sign(message: ByteArray): ByteArray
}

/** How a request proves who is calling. */
sealed interface GromCredential {
    /** A token from [GromConnectClient.login]. */
    data class Session(val token: String) : GromCredential

    /** A key the operator trusted on the phone (`services.*` scopes). */
    data class Key(val signer: GromSigner) : GromCredential
}

/**
 * A refusal with a stable code (`SCOPE_REQUIRED`, `UNAUTHORIZED`, …). Status
 * `0` means the request never reached an HTTP answer (`TRANSPORT_FAILED`) or the
 * document could not be understood (`BAD_RESPONSE`).
 */
class GromConnectException(
    val status: Int,
    val code: String,
    message: String
) : Exception(message)

/** A parsed service from `GET /v1/plugins`. */
data class GromService(
    val id: String,
    val title: String,
    val description: String,
    val capabilities: List<String>,
    val scopedOps: List<String>,
    /** Present only when the caller also holds `status.read`. */
    val status: Map<String, Any?>?
) {

    /** True when a `services.call` credential may invoke [op] on this service. */
    fun callable(op: String): Boolean = op in scopedOps

    internal companion object {
        fun listFrom(raw: Any?): List<GromService> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"] as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val status = map["status"] as? Map<String, Any?>
                GromService(
                    id = id,
                    title = map["title"] as? String ?: id,
                    description = map["description"] as? String ?: "",
                    capabilities = strings(map["capabilities"]),
                    scopedOps = strings(map["scopedOps"]),
                    status = status
                )
            }
        }

        private fun strings(raw: Any?): List<String> =
            (raw as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }
}

/** The parsed `GET /grom/v1/connect` document (D91). */
class GromConnect private constructor(
    val raw: Map<String, Any?>,
    val schema: String,
    val service: String,
    val api: Int,
    val gromVersion: String,
    val minApi: Int,
    val maxApi: Int,
    val state: String,
    val timeMs: Long,
    val principalKind: String,
    val principalNodeId: String?,
    val capabilities: List<String>,
    val servicesListed: Boolean,
    val services: List<GromService>,
    val authMethods: List<String>,
    val dataPlane: Map<String, Any?>
) {

    /** True when this server speaks the version [clientApi] implements. */
    fun supports(clientApi: Int = GromConnectClient.API_VERSION): Boolean =
        clientApi in minApi..maxApi

    /** True when the authenticated caller holds [scope]. */
    fun has(scope: String): Boolean = scope in capabilities

    companion object {

        internal fun from(body: Map<String, Any?>): GromConnect {
            val schema = body["schema"] as? String
                ?: throw bad("the connect document has no schema")
            val api = (body["api"] as? Number)?.toInt()
                ?: throw bad("the connect document has no api version")
            val compat = body["compat"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val minApi = (compat["minApi"] as? Number)?.toInt() ?: api
            val maxApi = (compat["maxApi"] as? Number)?.toInt() ?: api
            val auth = body["auth"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val services = body["services"] as? Map<*, *>
            val principal = body["principal"] as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            val dataPlane = body["dataPlane"] as? Map<String, Any?> ?: emptyMap()
            return GromConnect(
                raw = body,
                schema = schema,
                service = body["service"] as? String ?: "",
                api = api,
                gromVersion = body["grom"] as? String ?: "unknown",
                minApi = minApi,
                maxApi = maxApi,
                state = body["state"] as? String ?: "unknown",
                timeMs = (body["timeMs"] as? Number)?.toLong() ?: 0L,
                principalKind = principal?.get("kind") as? String ?: "anonymous",
                principalNodeId = principal?.get("nodeId") as? String,
                capabilities = strings(body["capabilities"]),
                servicesListed = services?.get("listed") as? Boolean ?: false,
                services = GromService.listFrom(services?.get("items")),
                authMethods = strings(auth["methods"]),
                dataPlane = dataPlane
            )
        }

        private fun strings(raw: Any?): List<String> =
            (raw as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        private fun bad(message: String): GromConnectException =
            GromConnectException(0, "BAD_CONNECT_DOCUMENT", message)
    }
}

/** A password session: a random token that expires (S5-R014). */
data class GromSession(val token: String, val expiresAtMs: Long)

/** The successful answer of one plugin call. */
data class GromRpcResult(
    val plugin: String,
    val op: String,
    val result: Map<String, Any?>
)

/**
 * The default transport: `HttpURLConnection`, no dependencies, no redirects and
 * a bounded read. TLS is whatever the URL says; Grom itself is plain HTTP on a
 * loopback or LAN address (D47), so a host that needs confidentiality through a
 * hostile network should point [GromConnectClient] at a tunnel it trusts.
 */
class UrlConnectionTransport(private val timeoutMs: Int = 8_000) : GromTransport {

    override fun execute(request: GromHttpRequest): GromHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            if (request.body.isNotEmpty()) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { readCapped(it) } ?: ""
            return GromHttpResponse(status, body, headersOf(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun headersOf(connection: HttpURLConnection): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, values) in connection.headerFields) {
            if (name == null || values.isEmpty()) continue
            out[name.lowercase()] = values.first()
        }
        return out
    }

    private fun readCapped(reader: java.io.Reader): String {
        val out = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            out.append(buffer, 0, read)
            if (out.length > GromJson.MAX_INPUT_CHARS) {
                throw GromConnectException(0, "BAD_RESPONSE", "the response is too large")
            }
        }
        return out.toString()
    }
}
