/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.a2a.client

import com.embabel.common.util.EmbabelObjectMapperHolder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.a2a.spec.AgentCapabilities
import io.a2a.spec.AgentCard
import io.a2a.spec.AgentInterface
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake remote A2A agent for [EmbabelA2AClient] tests: a local HTTP server that serves an agent card
 * at `/.well-known/agent.json` and answers JSON-RPC calls at its base URL with [jsonRpcResponder].
 *
 * @param cardDelayMillis delay before the card is returned, so concurrent callers overlap
 * @param jsonRpcResponder builds the JSON-RPC response body from the request's `id`
 */
class A2ATestAgent(
    private val cardDelayMillis: Long = 0,
    private val jsonRpcResponder: (requestId: Any?) -> String = { id -> jsonRpcResult(id, "null") },
) : AutoCloseable {

    private val objectMapperHolder = EmbabelObjectMapperHolder.createDefault()
    private val executor: ExecutorService = Executors.newFixedThreadPool(SERVER_THREADS)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)

    /** Number of agent-card requests served. */
    val cardRequests = AtomicInteger()

    val baseUrl: String = "http://localhost:${server.address.port}"

    init {
        val cardJson = objectMapperHolder.get().writeValueAsString(card(baseUrl)).toByteArray()
        server.executor = executor
        server.createContext("/.well-known/agent.json") { exchange ->
            cardRequests.incrementAndGet()
            if (cardDelayMillis > 0) Thread.sleep(cardDelayMillis)
            respond(exchange, cardJson)
        }
        server.createContext("/") { exchange ->
            val request = objectMapperHolder.get().readTree(exchange.requestBody.readAllBytes())
            val id = request.get("id")?.let { if (it.isNumber) it.numberValue() else it.asString() }
            respond(exchange, jsonRpcResponder(id).toByteArray())
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respond(exchange: HttpExchange, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    companion object {

        const val CARD_NAME = "test-remote-agent"

        private const val SERVER_THREADS = 8

        /** A JSON-RPC 2.0 success response whose `result` is the given raw JSON. */
        fun jsonRpcResult(id: Any?, resultJson: String): String {
            val idJson = when (id) {
                null -> "null"
                is Number -> id.toString()
                else -> "\"$id\""
            }
            return """{"jsonrpc":"2.0","id":$idJson,"result":$resultJson}"""
        }

        /** A valid A2A 0.3 agent card for an agent at [url], speaking JSON-RPC, without streaming. */
        fun card(url: String): AgentCard =
            AgentCard.Builder()
                .name(CARD_NAME)
                .description("Fake remote agent for EmbabelA2AClient tests")
                .url(url)
                .version("1.0.0")
                .capabilities(AgentCapabilities.Builder().streaming(false).build())
                .defaultInputModes(listOf("text/plain"))
                .defaultOutputModes(listOf("text/plain"))
                .skills(emptyList())
                .protocolVersion("0.3.0")
                .preferredTransport("JSONRPC")
                .additionalInterfaces(listOf(AgentInterface("JSONRPC", url)))
                .build()
    }
}
