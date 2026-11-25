package com.mobileharness.code.grom

import app.grom.sdk.connect.GromRpcResult
import app.grom.sdk.connect.GromService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GromLlmProxyAdapterTest {

    private class StubGateway(
        phase: GromPhase,
        private val services: List<GromService>,
        private val result: Map<String, Any?> = emptyMap(),
    ) : GromServiceGateway {
        override val snapshot = MutableStateFlow(GromConnectionSnapshot(phase = phase, services = services))
        override suspend fun connect() = snapshot.value
        override suspend fun call(service: String, op: String, args: Map<String, Any?>) =
            GromRpcResult(service, op, result)
        override suspend fun services() = services
        override fun disconnect() {}
    }

    private fun llmService(scopedOps: List<String> = listOf("chat.completions", "messages")) = GromService(
        id = "llm",
        title = "LLM Proxy",
        description = "",
        capabilities = listOf("llm.proxy", "chat.completions", "messages"),
        scopedOps = scopedOps,
        status = null,
    )

    @Test
    fun notConnectedIsUnavailable() = runBlocking {
        val gateway = StubGateway(GromPhase.DISCONNECTED, emptyList())
        val outcome = GromLlmProxyAdapter(gateway).chatCompletions(mapOf("model" to "auto"))
        assertTrue(outcome is GromLlmOutcome.Unavailable)
    }

    @Test
    fun noProxyServiceIsUnavailable() = runBlocking {
        val gateway = StubGateway(GromPhase.CONNECTED, emptyList())
        val outcome = GromLlmProxyAdapter(gateway).chatCompletions(mapOf("model" to "auto"))
        assertTrue(outcome is GromLlmOutcome.Unavailable)
    }

    @Test
    fun opNotInScopedOpsIsUnavailable() = runBlocking {
        val gateway = StubGateway(GromPhase.CONNECTED, listOf(llmService(scopedOps = emptyList())))
        val outcome = GromLlmProxyAdapter(gateway).chatCompletions(mapOf("model" to "auto"))
        assertTrue(outcome is GromLlmOutcome.Unavailable)
    }

    @Test
    fun chatCompletionsRoutesThroughProxyService() = runBlocking {
        val gateway = StubGateway(
            GromPhase.CONNECTED,
            listOf(llmService()),
            result = mapOf("choices" to listOf(mapOf("message" to mapOf("content" to "hi")))),
        )
        val outcome = GromLlmProxyAdapter(gateway).chatCompletions(mapOf("model" to "auto", "messages" to emptyList<Any>()))
        assertTrue(outcome is GromLlmOutcome.Success)
        assertEquals("hi", (outcome as GromLlmOutcome.Success).let {
            @Suppress("UNCHECKED_CAST")
            val choices = it.result["choices"] as List<Map<String, Any?>>
            ((choices.first()["message"] as Map<String, Any?>)["content"])
        })
    }

    @Test
    fun isAvailableIsFalseWhenNoProxyService() = runBlocking {
        val gateway = StubGateway(GromPhase.CONNECTED, emptyList())
        assertTrue(!GromLlmProxyAdapter(gateway).isAvailable())
    }

    @Test
    fun isAvailableIsTrueWithProxyService() = runBlocking {
        val gateway = StubGateway(GromPhase.CONNECTED, listOf(llmService()))
        assertTrue(GromLlmProxyAdapter(gateway).isAvailable())
    }
}
