package com.mobileharness.code.grom

import app.grom.sdk.connect.GromConnectClient
import app.grom.sdk.connect.GromConnectException
import app.grom.sdk.connect.GromCredential
import app.grom.sdk.connect.GromRpcResult
import app.grom.sdk.connect.GromService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The one internal interface through which other modules use GROM.
 *
 * A module never speaks Grom's protocol or opens its own connection; it checks
 * [snapshot], optionally calls [connect], and issues [call] on a service op the
 * server has published to scoped callers. This is the "one connection for the
 * whole app" boundary and is deliberately independent of AI providers.
 */
interface GromServiceGateway {
    val snapshot: StateFlow<GromConnectionSnapshot>
    suspend fun connect(): GromConnectionSnapshot
    suspend fun call(service: String, op: String, args: Map<String, Any?> = emptyMap()): GromRpcResult
    suspend fun services(): List<GromService>
    fun disconnect()
}

/**
 * Central, optional GROM connection manager (S6 client).
 *
 * OFF by default. When OFF there is no client, no session, no polling and no
 * reconnect; modules that need GROM read [snapshot] and show a clear "turn GROM
 * on" message. Turning OFF never touches the Grom server itself — it only drops
 * this app's client state while keeping the address and any saved credential.
 */
class GromConnectionManager(
    private val configStore: GromConfigStore,
    private val secretStore: GromSecretStore,
    private val clientFactory: (String) -> GromConnectClient = { GromConnectClient(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GromServiceGateway {

    private val _snapshot = MutableStateFlow(initialSnapshot())
    override val snapshot: StateFlow<GromConnectionSnapshot> = _snapshot.asStateFlow()

    @Volatile private var client: GromConnectClient? = null
    @Volatile private var credential: GromCredential? = null
    @Volatile private var epoch: Int = 0

    // ---------------------------------------------------------------- config

    fun config(): GromConfig = configStore.load()

    fun setEnabled(enabled: Boolean) {
        val cfg = configStore.load()
        if (cfg.enabled == enabled) return
        configStore.save(cfg.copy(enabled = enabled))
        epoch++
        client = null
        credential = null
        _snapshot.value = if (enabled) {
            GromConnectionSnapshot(phase = GromPhase.DISCONNECTED, baseUrl = cfg.baseUrl, enabled = true)
        } else {
            GromConnectionSnapshot(phase = GromPhase.OFF, baseUrl = cfg.baseUrl, enabled = false)
        }
    }

    fun setBaseUrl(raw: String) {
        val normalized = runCatching { GromConnectClient.normalizeBaseUrl(raw) }.getOrNull() ?: return
        val cfg = configStore.load()
        if (cfg.baseUrl == normalized) return
        configStore.save(cfg.copy(baseUrl = normalized))
        epoch++
        client = null
        credential = null
        _snapshot.value = GromConnectionSnapshot(
            phase = if (cfg.enabled) GromPhase.DISCONNECTED else GromPhase.OFF,
            baseUrl = normalized,
            enabled = cfg.enabled,
        )
    }

    fun setAuthMethod(method: String) {
        if (method != GromConfig.AUTH_PASSWORD && method != GromConfig.AUTH_KEY) return
        val cfg = configStore.load()
        if (cfg.authMethod == method) return
        configStore.save(cfg.copy(authMethod = method))
        epoch++
        client = null
        credential = null
        _snapshot.value = _snapshot.value.copy(phase = if (cfg.enabled) GromPhase.DISCONNECTED else GromPhase.OFF, message = null)
    }

    // ------------------------------------------------------------ credentials

    fun savePassword(password: String) {
        if (password.isNotBlank()) secretStore.put(SECRET_PASSWORD, password)
    }

    fun hasPassword(): Boolean = secretStore.get(SECRET_PASSWORD) != null

    fun clearPassword() = secretStore.remove(SECRET_PASSWORD)

    /**
     * Generates and persists a client Ed25519 identity (S6-R015). The private key
     * goes to [secretStore]; only the id + public key land in [configStore] so the
     * operator can paste the public key into Grom's "Trust a key" screen. Returns
     * null when the platform cannot generate Ed25519 (pre-Android-13), so the UI
     * can say "use password auth instead".
     */
    fun generateKey(): GromKeyIdentity? = runCatching {
        val pair = GromSignerFactory.generateKeyPair()
        val keyId = GromSignerFactory.keyId(pair.public)
        val publicKey = GromSignerFactory.publicKeyBase64(pair.public)
        val privateKey = GromSignerFactory.privateKeyBase64(pair.private)
        secretStore.put(SECRET_KEY, privateKey)
        configStore.save(configStore.load().copy(keyId = keyId, publicKey = publicKey))
        GromKeyIdentity(keyId, publicKey)
    }.getOrNull()

    fun hasKey(): Boolean =
        configStore.load().keyId.isNotBlank() && secretStore.get(SECRET_KEY) != null

    fun clearKey() {
        secretStore.remove(SECRET_KEY)
        configStore.save(configStore.load().copy(keyId = "", publicKey = ""))
    }

    // ------------------------------------------------------------- lifecycle

    override suspend fun connect(): GromConnectionSnapshot {
        val cfg = configStore.load()
        if (!cfg.enabled) {
            epoch++
            client = null
            credential = null
            setSnapshot(GromConnectionSnapshot(phase = GromPhase.OFF, baseUrl = cfg.baseUrl, enabled = false))
            return _snapshot.value
        }
        val startEpoch = epoch
        setSnapshot(GromConnectionSnapshot(phase = GromPhase.CONNECTING, baseUrl = cfg.baseUrl, enabled = true))
        val result = withContext(dispatcher) { doConnect(cfg) }
        if (startEpoch != epoch) return _snapshot.value // disconnected mid-flight
        setSnapshot(result)
        return result
    }

    /**
     * Fresh connect with a bounded, backoff retry on transport failure only
     * (S6-R027). Auth/permission/incompatibility failures are terminal: they are
     * reported, never retried in a loop. Callers should cancel their own scope on
     * disconnect; each attempt re-reads `enabled` so OFF stops it promptly.
     */
    suspend fun reconnect(maxAttempts: Int = 3, baseDelayMs: Long = 700): GromConnectionSnapshot {
        var result = _snapshot.value
        for (attempt in 1..maxAttempts) {
            if (!configStore.load().enabled) break
            result = connect()
            if (result.phase != GromPhase.DISCONNECTED || attempt == maxAttempts) break
            delay(baseDelayMs * attempt)
        }
        return result
    }

    override fun disconnect() {
        epoch++
        client = null
        credential = null
        val cfg = configStore.load()
        setSnapshot(
            GromConnectionSnapshot(
                phase = if (cfg.enabled) GromPhase.DISCONNECTED else GromPhase.OFF,
                baseUrl = cfg.baseUrl,
                enabled = cfg.enabled,
            ),
        )
    }

    // -------------------------------------------------------- service access

    override suspend fun call(service: String, op: String, args: Map<String, Any?>): GromRpcResult {
        val c = client ?: throw GromConnectException(0, "NOT_CONNECTED", "GROM is not connected")
        val cred = credential ?: throw GromConnectException(0, "NOT_CONNECTED", "no GROM credential")
        return withContext(dispatcher) { c.rpc(cred, service, op, args) }
    }

    override suspend fun services(): List<GromService> {
        val c = client ?: return emptyList()
        val cred = credential ?: return emptyList()
        return withContext(dispatcher) {
            runCatching { c.services(cred) }.getOrDefault(emptyList())
        }
    }

    // ---------------------------------------------------------------- internals

    private fun initialSnapshot(): GromConnectionSnapshot {
        val cfg = configStore.load()
        return GromConnectionSnapshot(
            phase = if (cfg.enabled) GromPhase.DISCONNECTED else GromPhase.OFF,
            baseUrl = cfg.baseUrl,
            enabled = cfg.enabled,
        )
    }

    private fun setSnapshot(value: GromConnectionSnapshot) {
        _snapshot.value = value
    }

    private fun doConnect(cfg: GromConfig): GromConnectionSnapshot {
        return try {
            val c = clientFactory(cfg.baseUrl)
            client = c

            val anonymous = c.connect()
            if (!anonymous.supports(GromConnectClient.API_VERSION)) {
                client = null
                GromConnectionSnapshot(
                    phase = GromPhase.INCOMPATIBLE,
                    baseUrl = cfg.baseUrl,
                    enabled = true,
                    serverVersion = anonymous.gromVersion,
                    apiVersion = anonymous.api,
                    message = "Grom speaks data API ${anonymous.minApi}..${anonymous.maxApi}; this app implements ${GromConnectClient.API_VERSION}",
                )
            } else {
                when (val resolution = resolveCredential(c, cfg, anonymous.authMethods)) {
                    is CredentialResolution.NeedsAuth -> {
                        client = null
                        GromConnectionSnapshot(
                            phase = GromPhase.AUTH_REQUIRED,
                            baseUrl = cfg.baseUrl,
                            enabled = true,
                            serverVersion = anonymous.gromVersion,
                            apiVersion = anonymous.api,
                            schema = anonymous.schema,
                            authMethods = resolution.methods,
                            message = resolution.reason,
                        )
                    }
                    is CredentialResolution.Ready -> {
                        credential = resolution.credential
                        val info = c.connect(resolution.credential)
                        val services = if (info.has("services.read")) c.services(resolution.credential) else emptyList()
                        GromConnectionSnapshot(
                            phase = GromPhase.CONNECTED,
                            baseUrl = cfg.baseUrl,
                            enabled = true,
                            serverVersion = info.gromVersion,
                            apiVersion = info.api,
                            schema = info.schema,
                            principal = info.principalKind,
                            capabilities = info.capabilities,
                            services = services,
                            authMethods = info.authMethods,
                        )
                    }
                }
            }
        } catch (e: GromConnectException) {
            client = null
            credential = null
            val phase = when (e.code) {
                "UNAUTHORIZED", "SIGNATURE_UNKNOWN_KEY", "SIGNATURE_STALE", "SIGNATURE_REPLAYED" -> GromPhase.AUTH_REQUIRED
                "TRANSPORT_FAILED" -> GromPhase.DISCONNECTED
                else -> GromPhase.ERROR
            }
            GromConnectionSnapshot(
                phase = phase,
                baseUrl = cfg.baseUrl,
                enabled = true,
                message = if (e.code.isNotEmpty()) "${e.code}: ${e.message}" else e.message,
            )
        } catch (e: Exception) {
            client = null
            credential = null
            GromConnectionSnapshot(phase = GromPhase.ERROR, baseUrl = cfg.baseUrl, enabled = true, message = e.message ?: "unexpected failure")
        }
    }

    private fun resolveCredential(
        c: GromConnectClient,
        cfg: GromConfig,
        methods: List<String>,
    ): CredentialResolution = when (cfg.authMethod) {
        GromConfig.AUTH_KEY -> resolveKeyCredential(cfg, methods)
        else -> resolvePasswordCredential(c, methods)
    }

    private fun resolveKeyCredential(cfg: GromConfig, methods: List<String>): CredentialResolution {
        val privateKey = secretStore.get(SECRET_KEY)?.let { GromSignerFactory.parsePrivateKey(it) }
        val keyId = cfg.keyId
        return when {
            "key" !in methods ->
                CredentialResolution.NeedsAuth("Grom does not accept key auth on this server", methods)
            privateKey == null || keyId.isBlank() ->
                CredentialResolution.NeedsAuth("No client key is configured — generate one and trust it in Grom", methods)
            else -> CredentialResolution.Ready(GromCredential.Key(GromSignerFactory.signer(privateKey, keyId)))
        }
    }

    private fun resolvePasswordCredential(c: GromConnectClient, methods: List<String>): CredentialResolution {
        if ("password" !in methods) {
            return CredentialResolution.NeedsAuth("Grom does not accept password login on this server", methods)
        }
        val password = secretStore.get(SECRET_PASSWORD)
        if (password == null) {
            return CredentialResolution.NeedsAuth("Enter the Grom public-API password", methods)
        }
        return try {
            CredentialResolution.Ready(c.session(c.login(password).token))
        } catch (e: GromConnectException) {
            CredentialResolution.NeedsAuth("Login refused: ${e.code}", methods)
        }
    }

    private sealed interface CredentialResolution {
        data class Ready(val credential: GromCredential) : CredentialResolution
        data class NeedsAuth(val reason: String, val methods: List<String>) : CredentialResolution
    }

    companion object {
        const val SECRET_PASSWORD: String = "grom_public_api_password"
        const val SECRET_KEY: String = "grom_ed25519_private_key"
    }
}

data class GromKeyIdentity(val keyId: String, val publicKey: String)
