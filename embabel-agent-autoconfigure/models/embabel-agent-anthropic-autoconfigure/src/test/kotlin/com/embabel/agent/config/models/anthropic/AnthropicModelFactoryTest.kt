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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.spi.InvalidApiKeyException
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.ObservationRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.function.Supplier

class AnthropicModelFactoryTest {

    private val restClientBuilder = mockk<ObjectProvider<RestClient.Builder>> {
        every { getIfAvailable(any<Supplier<RestClient.Builder>>()) } returns RestClient.builder()
        every { ifAvailable(any()) } just Runs
    }

    @Test
    fun `build returns SpringAiLlmService with correct name and provider`() {
        val factory = AnthropicModelFactory(
            apiKey = "test-key",
            observationRegistry = ObservationRegistry.NOOP,
            restClientBuilder = restClientBuilder,
        )
        val service = factory.build(model = AnthropicModels.CLAUDE_HAIKU_4_5) as SpringAiLlmService
        assertEquals(AnthropicModels.CLAUDE_HAIKU_4_5, service.name)
        assertEquals(AnthropicModels.PROVIDER, service.provider)
    }

    @Test
    fun `build with custom baseUrl constructs without error`() {
        val factory = AnthropicModelFactory(
            apiKey = "test-key",
            baseUrl = "https://custom.anthropic.example.com",
            observationRegistry = ObservationRegistry.NOOP,
            restClientBuilder = restClientBuilder,
        )
        val service = factory.build(model = AnthropicModels.CLAUDE_HAIKU_4_5) as SpringAiLlmService
        assertTrue(service.name.isNotEmpty())
    }
}

class AnthropicModelFactoryBuildValidatedTest {

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

    private fun factory() = AnthropicModelFactory(
        apiKey = "test-key",
        baseUrl = "http://localhost:$port",
        observationRegistry = ObservationRegistry.NOOP,
        restClientBuilder = restClientBuilder,
    )

    @Test
    fun `buildValidated throws InvalidApiKeyException on 401`() {
        server.createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """{"type":"error","error":{"type":"authentication_error","message":"Invalid API Key"}}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        assertThrows<InvalidApiKeyException> {
            factory().buildValidated()
        }
    }

    @Test
    fun `buildValidated returns LlmService on 200`() {
        server.createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """
                {
                  "id": "msg_test",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "Hi"}],
                  "model": "${AnthropicModels.CLAUDE_HAIKU_4_5}",
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "usage": {"input_tokens": 5, "output_tokens": 2}
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val service = factory().buildValidated()
        assertNotNull(service)
        assertEquals(AnthropicModels.PROVIDER, service.provider)
    }

    @Test
    fun `buildValidated with explicit model throws InvalidApiKeyException on 401`() {
        server.createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """{"type":"error","error":{"type":"authentication_error","message":"Invalid API Key"}}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        assertThrows<InvalidApiKeyException> {
            factory().buildValidated(AnthropicModels.CLAUDE_HAIKU_4_5)
        }
    }
}
