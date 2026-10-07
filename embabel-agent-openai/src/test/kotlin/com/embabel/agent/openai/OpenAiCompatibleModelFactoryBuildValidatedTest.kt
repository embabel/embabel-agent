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

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.common.byok.BLANK_API_KEY_MESSAGE
import com.embabel.common.byok.InvalidApiKeyException
import com.embabel.common.ai.model.PricingModel
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.ObservationRegistry
import com.openai.errors.OpenAIServiceException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class OpenAiCompatibleModelFactoryBuildValidatedTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private val restClientBuilder = mockk<ObjectProvider<RestClient.Builder>> {
        every { getIfAvailable(any<Supplier<RestClient.Builder>>()) } returns RestClient.builder()
        every { ifAvailable(any()) } just Runs
    }

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun factory() = OpenAiCompatibleModelFactory(
        baseUrl = "http://localhost:$port",
        apiKey = "test-key",
        completionsPath = null,
        embeddingsPath = null,
        observationRegistry = ObservationRegistry.NOOP,
        restClientBuilder = restClientBuilder,
    )

    @Test
    fun `buildValidated throws InvalidApiKeyException on 401`() {
        // Wildcard root handler — Spring AI 2.0 + openai-java 4.x may hit any of
        // /v1/chat/completions, /v1/responses, or /chat/completions depending on
        // the SDK's endpoint routing; we don't care which path is used for this probe.
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        assertThrows<InvalidApiKeyException> {
            factory().buildValidated(
                model = OpenAiModels.GPT_41_MINI,
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                provider = OpenAiModels.PROVIDER,
                knowledgeCutoffDate = null,
            )
        }
    }

    @Test
    fun `buildValidated returns LlmService on 200`() {
        // Wildcard root handler — same reasoning as the 401 test above.
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1234567890,
                  "model": "${OpenAiModels.GPT_41_MINI}",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hi"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3}
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val service = factory().buildValidated(
            model = OpenAiModels.GPT_41_MINI,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = null,
        )
        assertNotNull(service)
    }

    @Test
    fun `buildValidated rejects a blank key without calling the provider`() {
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()

        val blankKeyFactory = OpenAiCompatibleModelFactory(
            baseUrl = "http://localhost:$port",
            apiKey = "   ",
            completionsPath = null,
            embeddingsPath = null,
            observationRegistry = ObservationRegistry.NOOP,
            restClientBuilder = restClientBuilder,
        )

        val e = assertThrows<InvalidApiKeyException> {
            blankKeyFactory.buildValidated(
                model = OpenAiModels.GPT_41_MINI,
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                provider = OpenAiModels.PROVIDER,
                knowledgeCutoffDate = null,
            )
        }
        assertEquals(BLANK_API_KEY_MESSAGE, e.message)
        assertEquals(0, requests, "a blank key must not reach the provider")
    }

    @Test
    fun `the ByokSpec entry points reject a blank key too`() {
        listOf(
            OpenAiCompatibleModelFactory.openAi("   "),
            OpenAiCompatibleModelFactory.deepSeek(""),
            OpenAiCompatibleModelFactory.mistral("\t"),
            OpenAiCompatibleModelFactory.gemini(" "),
            OpenAiCompatibleModelFactory.atlasCloud("\n"),
        ).forEach { spec ->
            val e = assertThrows<InvalidApiKeyException> { spec.buildValidated() }
            assertEquals(BLANK_API_KEY_MESSAGE, e.message)
        }
    }

    private fun validate() = factory().buildValidated(
        model = OpenAiModels.GPT_41_MINI,
        pricingModel = PricingModel.ALL_YOU_CAN_EAT,
        provider = OpenAiModels.PROVIDER,
        knowledgeCutoffDate = null,
    )

    /** The number of requests the local server has received from [answerWith]'s handler. */
    private val requestCount = AtomicInteger()

    private fun answerWith(status: Int, body: String) {
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    /**
     * The provider knows the key and refuses the request because the account has no credit. The
     * exception reports status code 402, and its cause is the SDK's exception.
     */
    @Test
    fun `a 402 response reports status code 402 and keeps the SDK exception as the cause`() {
        answerWith(402, """{"error":{"message":"Your prepayment credits are depleted.","status":"RESOURCE_EXHAUSTED"}}""")

        val e = assertThrows<InvalidApiKeyException> { validate() }

        assertEquals(402, e.statusCode)
        assertInstanceOf(OpenAIServiceException::class.java, e.cause, "the SDK's exception is the cause")
    }

    @Test
    fun `a 401 response reports status code 401 after one request`() {
        answerWith(401, """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""")

        assertEquals(401, assertThrows<InvalidApiKeyException> { validate() }.statusCode)
        assertEquals(1, requestCount.get(), "the SDK does not retry a 401")
    }

    /**
     * The provider knows the key and refuses the request because the key is rate limited. The SDK
     * sends the request, then retries it twice, so the provider receives three requests. The
     * exception reports status code 429.
     */
    @Test
    fun `a 429 response reports status code 429 after the SDK has retried twice`() {
        answerWith(429, """{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}""")

        assertEquals(429, assertThrows<InvalidApiKeyException> { validate() }.statusCode)
        assertEquals(3, requestCount.get(), "one request and two retries")
    }

    @Test
    fun `a refused connection reports no status code and keeps the cause`() {
        // Bind a free port and release it. Nothing listens on it afterwards, so the connection
        // is refused and the provider sends no response.
        port = ServerSocket(0).use { it.localPort }

        val e = assertThrows<InvalidApiKeyException> { validate() }

        assertNull(e.statusCode, "there was no response, so there is no status code")
        assertNotNull(e.cause)
    }

    @Test
    fun `a blank key reports no status code`() {
        val e = assertThrows<InvalidApiKeyException> { OpenAiCompatibleModelFactory.openAi(" ").buildValidated() }

        assertNull(e.statusCode)
    }
}
