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
import com.embabel.common.byok.InvalidApiKeyException
import com.embabel.common.ai.model.PricingModel
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.ObservationRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
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
}
