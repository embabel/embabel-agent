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

import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.byok.InvalidApiKeyException
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.TokenCountBatchingStrategy
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
    private var lastRequest: String = ""

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                lastRequest = exchange.requestBody.use { it.readBytes() }.decodeToString()
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

    private fun model(): EmbeddingModel =
        (factory().openAiCompatibleEmbeddingService(model = "any-model", provider = "Any") as SpringAiEmbeddingService)
            .model

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
    fun `an index and a usage the provider sends are the ones reported`() {
        answer = 200 to """
            {"object":"list","model":"text-embedding-3-small","data":[
              {"object":"embedding","index":1,"embedding":[0.4,0.5]},
              {"object":"embedding","index":0,"embedding":[0.1,0.2]}
            ],"usage":{"prompt_tokens":7,"total_tokens":9}}
        """.trimIndent()

        val response = model().call(EmbeddingRequest(listOf("first", "second"), null))

        assertEquals(listOf(1, 0), response.results.map { it.index })
        assertEquals(7, response.metadata.usage.promptTokens)
        assertEquals(9, response.metadata.usage.totalTokens)
    }

    @Test
    fun `an answer with no usage reports none`() {
        answer = 200 to """
            {"object":"list","model":"gemini-embedding-2","data":[
              {"object":"embedding","embedding":[0.1,0.2]}
            ]}
        """.trimIndent()

        val response = model().call(EmbeddingRequest(listOf("only"), null))

        assertEquals(listOf(0), response.results.map { it.index })
        assertEquals(0, response.metadata.usage.totalTokens)
    }

    @Test
    fun `a document in a batch is embedded with its metadata, as one embedded alone is`() {
        answer = 200 to """
            {"object":"list","model":"any-model","data":[
              {"object":"embedding","embedding":[0.1,0.2]}
            ]}
        """.trimIndent()
        val document = Document("the body", mapOf("chapter" to "the-chapter-marker"))
        val model = model()

        model.embed(listOf(document), null, TokenCountBatchingStrategy())
        assertTrue(lastRequest.contains("the-chapter-marker")) { "batch request dropped the metadata: $lastRequest" }

        lastRequest = ""
        model.embed(document)
        assertTrue(lastRequest.contains("the-chapter-marker")) { "single request dropped the metadata: $lastRequest" }
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
