package com.mobileharness.code.lsp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/** A scripted in-memory transport that pairs each outgoing request with a queued response. */
private class FakeTransport : LspTransport {
    val sent = mutableListOf<JSONObject>()
    private val responses = LinkedBlockingQueue<JSONObject>()

    fun queueResponse(json: JSONObject) = responses.add(json)

    override fun send(message: JSONObject) {
        sent += message
    }

    override fun nextMessage(): JSONObject? = responses.poll()

    override fun close() = Unit
}

class LspTest {

    @Test
    fun messageCodec_framesAndParsesContentLength() {
        val frame = LspMessageCodec.frame("""{"jsonrpc":"2.0"}""")
        assertTrue(frame.startsWith("Content-Length: "))
        val lines = frame.lines()
        val length = LspMessageCodec.parseContentLength(lines[0])
        assertNotNull(length)
        assertEquals("""{"jsonrpc":"2.0"}""".toByteArray(Charsets.UTF_8).size, length!!)
        assertEquals(null, LspMessageCodec.parseContentLength("Content-Type: json"))
    }

    @Test
    fun registry_registersAndFindsServerForFile() {
        val registry = LspServerRegistry()
        registry.register(
            LspServerDefinition(
                name = "kotlin",
                command = "kotlin-lsp",
                extensions = mapOf(".kt" to "kotlin", ".kts" to "kotlin"),
                languageIds = listOf("kotlin"),
                license = "Apache-2.0",
            ),
            installed = true,
            runtimeDependency = "java",
        )
        assertEquals(1, registry.list().size)
        val found = registry.findForFile("Main.kt")
        assertNotNull(found)
        assertEquals("kotlin", found!!.languageIdFor("Main.kt"))
        assertNull(registry.findForFile("main.py"))
    }

    @Test
    fun session_initializesAndRequests() {
        val transport = FakeTransport()
        // initialize response
        transport.queueResponse(LspMessageCodec.response(1, JSONObject().put("capabilities", JSONObject())))
        // definition response
        transport.queueResponse(LspMessageCodec.response(2, JSONArray().put(JSONObject().put("uri", "file:///w/Main.kt").put("range", JSONObject().put("start", JSONObject().put("line", 3).put("character", 1))))))

        val session = LspSession(
            LspServerDefinition(name = "kotlin", command = "kotlin-lsp", extensions = mapOf(".kt" to "kotlin")),
            transport,
            rootUri = "file:///w",
            rootPath = "/w",
        )
        val init = session.initialize()
        assertNotNull(init)
        val version = session.open("file:///w/Main.kt", "kotlin", "fun main() {}")
        assertEquals(1, version)
        val result = session.request("textDocument/definition", JSONObject().put("textDocument", JSONObject().put("uri", "file:///w/Main.kt")))
        assertNotNull(result)
        assertTrue(result is JSONArray)
    }

    @Test
    fun session_routesPublishedDiagnostics() {
        val transport = FakeTransport()
        transport.queueResponse(LspMessageCodec.response(1, JSONObject().put("capabilities", JSONObject())))
        val session = LspSession(
            LspServerDefinition(name = "ts", command = "ts-lsp", extensions = mapOf(".ts" to "typescript")),
            transport,
            rootUri = "file:///w",
            rootPath = "/w",
        )
        session.initialize()
        // Simulate a publishDiagnostics notification from the server arriving as the next message.
        val diagnostics = JSONArray().put(JSONObject().put("severity", 1).put("message", "type error").put("range", JSONObject().put("start", JSONObject().put("line", 0).put("character", 0))))
        val publish = JSONObject().put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics")
            .put("params", JSONObject().put("uri", "file:///w/a.ts").put("version", 1).put("diagnostics", diagnostics))
        transport.queueResponse(publish)
        // A request that consumes the publish message before its own response.
        transport.queueResponse(LspMessageCodec.response(2, JSONObject()))
        session.request("textDocument/hover", JSONObject())
        val stored = session.diagnostics("file:///w/a.ts")
        assertEquals(1, stored.length())
        assertEquals("type error", stored.getJSONObject(0).getString("message"))
    }

    @Test
    fun documentSync_reusesVersionForIdenticalText() {
        val sync = LspDocumentSync()
        val (v1, opened1) = sync.sync("file:///w/a.kt", "fun a() {}")
        assertEquals(1, v1)
        assertTrue(opened1)
        val (v2, opened2) = sync.sync("file:///w/a.kt", "fun a() {}")
        assertEquals(1, v2)
        assertTrue(!opened2)
        val (v3, _) = sync.sync("file:///w/a.kt", "fun a() { return }")
        assertEquals(2, v3)
    }

    @Test
    fun manager_resolvesServerAndSession() {
        val registry = LspServerRegistry()
        registry.register(LspServerDefinition(name = "kotlin", command = "kotlin-lsp", extensions = mapOf(".kt" to "kotlin")), installed = true)
        val manager = LspManager(registry) { _, _ -> FakeTransport() }
        val server = manager.serverForFile("Main.kt")
        assertNotNull(server)
        val session = manager.sessionFor(server!!, java.io.File("/workspace"))
        assertNotNull(session)
        manager.closeAll()
    }
}
