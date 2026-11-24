package com.mobileharness.code.lsp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Language Server Protocol (LSP) support for the on-device development environment.
 *
 * This package models and drives LSP servers (JSON-RPC over stdio, framed with `Content-Length`
 * headers). The process is started by an injected [LspTransport] — on Android that wraps a PRoot
 * `ProcessBuilder`; in unit tests it is an in-memory fake — so every operation (initialize,
 * didOpen/didChange, diagnostics, definition, references, hover, symbols, shutdown) is pure JVM and
 * fully testable without a real language server binary.
 */

/** A declared language server: how to launch it and which files it handles. */
data class LspServerDefinition(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** Map of lowercase file extension (".kt") → languageId ("kotlin"). */
    val extensions: Map<String, String> = emptyMap(),
    val languageIds: List<String> = emptyList(),
    val initializationOptions: JSONObject? = null,
    val license: String = "",
    val enabled: Boolean = true,
) {
    fun handlesFile(name: String): Boolean {
        val lower = name.lowercase()
        val byExt = extensions.keys.any { lower.endsWith(it) }
        val byName = extensions.keys.any { it.equals(lower, ignoreCase = true) }
        return byExt || byName
    }

    fun languageIdFor(fileName: String): String {
        val lower = fileName.lowercase()
        val ext = extensions.keys.firstOrNull { lower.endsWith(it) }
        return ext?.let { extensions.getValue(it) } ?: fileName.substringAfterLast('.', "text")
    }
}

/** Registry of installed/available LSP servers, with install/update/remove lifecycle metadata. */
class LspServerRegistry {
    data class Entry(
        val definition: LspServerDefinition,
        val installed: Boolean = false,
        val runtimeDependency: String? = null,
        val downloadSizeBytes: Long = 0L,
    )

    private val servers = LinkedHashMap<String, Entry>()

    fun register(definition: LspServerDefinition, installed: Boolean = false, runtimeDependency: String? = null, downloadSizeBytes: Long = 0L): Boolean {
        if (servers.containsKey(definition.name)) return false
        servers[definition.name] = Entry(definition, installed, runtimeDependency, downloadSizeBytes)
        return true
    }

    fun update(name: String, installed: Boolean? = null, runtimeDependency: String? = null, downloadSizeBytes: Long? = null) {
        val entry = servers[name] ?: return
        servers[name] = entry.copy(
            installed = installed ?: entry.installed,
            runtimeDependency = runtimeDependency ?: entry.runtimeDependency,
            downloadSizeBytes = downloadSizeBytes ?: entry.downloadSizeBytes,
        )
    }

    fun remove(name: String): Boolean = servers.remove(name) != null

    fun setEnabled(name: String, enabled: Boolean) {
        val entry = servers[name] ?: return
        servers[name] = entry.copy(definition = entry.definition.copy(enabled = enabled))
    }

    fun list(): List<Entry> = servers.values.toList()

    fun get(name: String): Entry? = servers[name]

    fun findForFile(fileName: String): LspServerDefinition? =
        servers.values.firstOrNull { it.installed && it.definition.enabled && it.definition.handlesFile(fileName) }?.definition

    fun installed(): List<Entry> = servers.values.filter { it.installed }

    fun missingRuntime(): List<Entry> = servers.values.filter { !it.installed && it.runtimeDependency != null }
}

/** Frames and parses LSP stdio messages (`Content-Length: N\r\n\r\n<body>`). */
object LspMessageCodec {
    fun frame(body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return "Content-Length: ${bytes.size}\r\n\r\n$body"
    }

    fun frame(message: JSONObject): String = frame(message.toString())

    /** Parses a `Content-Length` header line, or null when the line is not that header. */
    fun parseContentLength(line: String): Int? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("Content-Length:", ignoreCase = true)) return null
        return trimmed.substringAfter(':').trim().toIntOrNull()
    }

    fun request(id: Int, method: String, params: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    fun notify(method: String, params: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)

    fun response(id: Int, result: Any?): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result ?: JSONObject.NULL)
}

/**
 * Transport abstraction: a session writes requests/notifications and reads responses + server
 * requests. On Android this wraps the PRoot process; in tests it is a scripted fake.
 */
interface LspTransport {
    fun send(message: JSONObject)
    /** Blocking read of the next complete JSON-RPC message, or null on EOF. */
    fun nextMessage(): JSONObject?
    fun close()
}

/** Tracks document versions and texts for one LSP session. */
class LspDocumentSync {
    private val versions = ConcurrentHashMap<String, Int>()
    private val texts = ConcurrentHashMap<String, String>()

    /** Returns the new/unchanged version for [uri] after (re)opening with [text]. */
    fun sync(uri: String, text: String): Pair<Int, Boolean> {
        val key = uri
        val previous = versions[key]
        if (previous != null && texts[key] == text) return previous to false
        val version = (previous ?: 0) + 1
        versions[key] = version
        texts[key] = text
        return version to (previous == null)
    }

    fun version(uri: String): Int? = versions[uri]

    fun clear() {
        versions.clear()
        texts.clear()
    }
}

/** Stores published diagnostics per document URI, keyed by version. */
class LspDiagnosticsStore {
    data class Publish(val uri: String, val version: Int?, val diagnostics: JSONArray)

    private val byUri = ConcurrentHashMap<String, Publish>()

    fun publish(uri: String, version: Int?, diagnostics: JSONArray) {
        byUri[uri] = Publish(uri, version, diagnostics)
    }

    fun get(uri: String): Publish? = byUri[uri]

    fun clear() = byUri.clear()
}

/**
 * A single running LSP session: it initializes the server on first use, opens documents, issues
 * requests by id, and routes `textDocument/publishDiagnostics` into the diagnostics store.
 * Timeouts and process cleanup are injected so the class stays deterministic in tests.
 */
class LspSession(
    val definition: LspServerDefinition,
    private val transport: LspTransport,
    private val diagnostics: LspDiagnosticsStore = LspDiagnosticsStore(),
    private val rootUri: String = "file:///workspace",
    private val rootPath: String = "/workspace",
) {
    private val ids = AtomicInteger(1)
    private val documentSync = LspDocumentSync()
    @Volatile private var initialized = false

    fun initialize(): JSONObject? {
        if (initialized) return null
        val params = JSONObject()
            .put("processId", JSONObject.NULL)
            .put("rootUri", rootUri)
            .put("rootPath", rootPath)
            .put("workspaceFolders", JSONArray().put(JSONObject().put("uri", rootUri).put("name", "workspace")))
            .put("capabilities", JSONObject()
                .put("textDocument", JSONObject()
                    .put("publishDiagnostics", JSONObject().put("versionSupport", true))
                    .put("hover", JSONObject().put("contentFormat", JSONArray().put("markdown").put("plaintext")))
                    .put("documentSymbol", JSONObject().put("hierarchicalDocumentSymbolSupport", true))
                    .put("definition", JSONObject().put("linkSupport", true))))
            .apply { definition.initializationOptions?.let { put("initializationOptions", it) } }
        val result = rawRequest("initialize", params)
        send(LspMessageCodec.notify("initialized", JSONObject()))
        initialized = true
        return result as? JSONObject
    }

    fun open(uri: String, languageId: String, text: String): Int {
        initialize()
        val (version, isNew) = documentSync.sync(uri, text)
        if (isNew) {
            send(LspMessageCodec.notify("textDocument/didOpen", JSONObject()
                .put("textDocument", JSONObject().put("uri", uri).put("languageId", languageId).put("version", version).put("text", text))))
        } else {
            send(LspMessageCodec.notify("textDocument/didChange", JSONObject()
                .put("textDocument", JSONObject().put("uri", uri).put("version", version))
                .put("contentChanges", JSONArray().put(JSONObject().put("text", text)))))
        }
        return version
    }

    /** Issues a request and returns the raw result element (object, array, or null on error/EOF). */
    fun request(method: String, params: JSONObject): Any? {
        if (!initialized) initialize()
        return rawRequest(method, params)
    }

    private fun rawRequest(method: String, params: JSONObject): Any? {
        val id = ids.getAndIncrement()
        transport.send(LspMessageCodec.request(id, method, params))
        while (true) {
            val message = transport.nextMessage() ?: return null
            val messageId = message.optInt("id", -1)
            if (messageId == id) {
                if (message.has("error")) return null
                val result = message.opt("result")
                return if (result == null || result == JSONObject.NULL) null else result
            }
            handleServerMessage(message)
        }
    }

    fun diagnostics(uri: String): JSONArray = diagnostics.get(uri)?.diagnostics ?: JSONArray()

    fun shutdown() {
        runCatching { send(LspMessageCodec.request(ids.getAndIncrement(), "shutdown", JSONObject())) }
        runCatching { transport.close() }
    }

    private fun send(message: JSONObject) = transport.send(message)

    private fun handleServerMessage(message: JSONObject) {
        val method = message.optString("method")
        val id = message.optInt("id", -1)
        if (method == "textDocument/publishDiagnostics") {
            val params = message.optJSONObject("params") ?: return
            val uri = params.optString("uri")
            val version = if (params.isNull("version")) null else params.optInt("version")
            val diagnosticsArray = params.optJSONArray("diagnostics") ?: JSONArray()
            diagnostics.publish(uri, version, diagnosticsArray)
        } else if (id != -1 && method.isNotBlank()) {
            // Answer server→client requests the client must support, with empty results.
            val result: Any = when (method) {
                "workspace/workspaceFolders" -> JSONArray().put(JSONObject().put("uri", rootUri).put("name", "workspace"))
                else -> JSONObject.NULL
            }
            transport.send(LspMessageCodec.response(id, result))
        }
    }
}

/**
 * Central manager: resolves a server for a file, lazily starts one session per
 * (server, workspace root), and keeps diagnostics in the store. The process starter is injected so
 * the manager is unit-testable and a real PRoot spawn lives only in the Android layer.
 */
class LspManager(
    private val registry: LspServerRegistry,
    private val startTransport: (LspServerDefinition, File) -> LspTransport,
) {
    private val sessions = ConcurrentHashMap<String, LspSession>()
    private val diagnostics = LspDiagnosticsStore()

    fun servers(): List<LspServerRegistry.Entry> = registry.list()

    fun installed(): List<LspServerRegistry.Entry> = registry.installed()

    fun serverForFile(fileName: String): LspServerDefinition? = registry.findForFile(fileName)

    fun sessionFor(definition: LspServerDefinition, root: File): LspSession =
        sessions.computeIfAbsent("${definition.name}|${root.path}") {
            LspSession(definition, startTransport(definition, root), diagnostics, root.toURI().toString(), root.path)
        }

    fun diagnosticsFor(uri: String): JSONArray = diagnostics.get(uri)?.diagnostics ?: JSONArray()

    fun closeAll() {
        sessions.values.forEach { runCatching { it.shutdown() } }
        sessions.clear()
        diagnostics.clear()
    }
}
