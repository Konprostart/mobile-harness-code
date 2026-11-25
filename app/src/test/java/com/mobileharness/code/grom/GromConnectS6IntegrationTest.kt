package com.mobileharness.code.grom

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end S6 integration over real loopback HTTP. The real SDK
 * (`UrlConnectionTransport`) talks to a minimal real HTTP/1.1 server that
 * implements the data-plane routes, so the full wire path (TCP → HTTP → JSON →
 * signing → parsing) is exercised — discovery → login → services → rpc → Free
 * LLM adapter. The "server" is a test double only in that it is not Grom's own
 * engine process (which requires an Android device).
 */
class GromConnectS6IntegrationTest {

    private class MiniHttpServer(private val plane: FakeGromDataPlane) {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val running = AtomicBoolean(true)
        val port: Int get() = server.localPort

        fun start() {
            executor.submit {
                while (running.get()) {
                    try {
                        handle(server.accept())
                    } catch (_: Exception) {
                        // socket closed on stop()
                    }
                }
            }
        }

        fun stop() {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = runCatching { URI(parts[1]).path }.getOrDefault(parts[1])

                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val index = line.indexOf(':')
                    if (index > 0) {
                        val name = line.substring(0, index).trim().lowercase()
                        val value = line.substring(index + 1).trim()
                        headers[name] = value
                        if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buffer, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buffer, 0, read)
                } else {
                    ""
                }

                val (status, responseBody) = plane.handle(method, path, headers, body)
                val reason = when (status) {
                    200 -> "OK"
                    401 -> "Unauthorized"
                    403 -> "Forbidden"
                    404 -> "Not Found"
                    else -> "OK"
                }
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("HTTP/1.1 $status $reason\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private suspend fun withServer(plane: FakeGromDataPlane, block: suspend (Int) -> Unit) {
        val server = MiniHttpServer(plane)
        server.start()
        try {
            block(server.port)
        } finally {
            server.stop()
        }
    }

    private fun connectedManager(port: Int): GromConnectionManager {
        val configStore = MemoryConfigStore(GromConfig(baseUrl = "http://127.0.0.1:$port", enabled = true))
        val secrets = MemorySecretStore().apply { put(GromConnectionManager.SECRET_PASSWORD, "secret") }
        return GromConnectionManager(configStore, secrets)
    }

    @Test
    fun passwordFlowAndFreeLlmProxyOverRealHttp() = runBlocking {
        withServer(FakeGromDataPlane()) { port ->
            val manager = connectedManager(port)

            val connected = manager.connect()
            assertEquals(GromPhase.CONNECTED, connected.phase)
            assertEquals("0.16.0-server29", connected.serverVersion)
            assertEquals(listOf("agent", "llm"), connected.services.map { it.id })

            val rpc = manager.call("agent", "kv.put", mapOf("key" to "x", "value" to "y"))
            assertEquals("agent", rpc.plugin)
            assertEquals(true, rpc.result["stored"])

            val outcome = GromLlmProxyAdapter(manager).chatCompletions(
                mapOf("model" to "auto", "messages" to emptyList<Any>()),
            )
            assertTrue("Free LLM API must proxy through the llm service", outcome is GromLlmOutcome.Success)
        }
    }

    @Test
    fun freeLlmProxyUnavailableWhenNoProxyService() = runBlocking {
        val plane = FakeGromDataPlane(
            services = listOf(GromServiceDoc("agent", "Agent", "kv", listOf("kv.get"), listOf("kv.get"))),
        )
        withServer(plane) { port ->
            val manager = connectedManager(port)
            assertEquals(GromPhase.CONNECTED, manager.connect().phase)

            val outcome = GromLlmProxyAdapter(manager).chatCompletions(mapOf("model" to "auto"))
            assertTrue(outcome is GromLlmOutcome.Unavailable)
        }
    }

    @Test
    fun offBehaviourNeverContactsServer() = runBlocking {
        val plane = FakeGromDataPlane()
        val server = MiniHttpServer(plane)
        server.start()
        try {
            val configStore = MemoryConfigStore(
                GromConfig(baseUrl = "http://127.0.0.1:${server.port}", enabled = false),
            )
            val manager = GromConnectionManager(configStore, MemorySecretStore())
            val result = manager.connect()
            assertEquals(GromPhase.OFF, result.phase)
        } finally {
            server.stop()
        }
    }
}
