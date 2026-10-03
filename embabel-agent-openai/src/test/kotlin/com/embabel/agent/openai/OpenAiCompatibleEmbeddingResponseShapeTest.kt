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
package com.embabel.agent.openai

import com.embabel.common.byok.InvalidApiKeyException
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress

/**
 * What an OpenAI-compatible provider actually sends back from `/embeddings`, through the real
 * client and the real response parsing.
 *
 * The OpenAI spec marks each item's `index` and the response's `usage` as required, and OpenAI
 * sends both. Google's OpenAI-compatible endpoint sends neither — `data[i]` is `{object, embedding}`
 * and the top level is `{object, data, model}` — so a Gemini key could never build an embedding
 * service: the probe failed with "`index` is not set" and was reported as an invalid API key.
 */
class OpenAiCompatibleEmbeddingResponseShapeTest {

    private lateinit var server: HttpServer
    private var answer: Pair<Int, String> = 200 to "{}"

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val (status, body) = answer
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun factory() = OpenAiCompatibleModelFactory(
        baseUrl = "http://localhost:${server.address.port}",
        apiKey = "test-key",
        observationRegistry = ObservationRegistry.NOOP,
    )

    @Test
    fun `an answer with no index and no usage, as Google sends it, builds and embeds in order`() {
        answer = 200 to """
            {"object":"list","model":"gemini-embedding-2","data":[
              {"object":"embedding","embedding":[0.1,0.2,0.3]},
              {"object":"embedding","embedding":[0.4,0.5,0.6]}
            ]}
        """.trimIndent()

        val service = factory().buildValidatedEmbeddingService(model = "gemini-embedding-2", provider = "GoogleGenAI")

        assertEquals(3, service.dimensions)
        val vectors = service.embed(listOf("first", "second"))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), vectors[0])
        assertArrayEquals(floatArrayOf(0.4f, 0.5f, 0.6f), vectors[1])
    }

    @Test
    fun `an answer that carries index and usage, as OpenAI sends it, still builds`() {
        answer = 200 to """
            {"object":"list","model":"text-embedding-3-small","data":[
              {"object":"embedding","index":0,"embedding":[0.1,0.2]}
            ],"usage":{"prompt_tokens":1,"total_tokens":1}}
        """.trimIndent()

        val service = factory().buildValidatedEmbeddingService(model = "text-embedding-3-small", provider = "OpenAI")

        assertEquals(2, service.dimensions)
    }

    @Test
    fun `a refused probe keeps the provider's failure as its cause`() {
        answer = 404 to """{"error":{"code":404,"message":"model not found","status":"NOT_FOUND"}}"""

        val e = assertThrows<InvalidApiKeyException> {
            factory().buildValidatedEmbeddingService(model = "no-such-model", provider = "GoogleGenAI")
        }

        assertNotNull(e.cause, "the provider's exception explains the refusal and must not be dropped")
    }
}
