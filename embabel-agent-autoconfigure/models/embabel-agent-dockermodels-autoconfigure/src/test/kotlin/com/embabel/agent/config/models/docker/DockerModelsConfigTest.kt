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
package com.embabel.agent.config.models.docker

import com.embabel.agent.openai.withOpenAiReasoningEffort
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.util.ObjectProviders
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class DockerModelsConfigTest {

    private lateinit var server: HttpServer

    private val chatRequests = AtomicInteger()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/engines/v1/models") { exchange ->
            val body = """{"object":"list","data":[{"id":"chat-model"},{"id":"embed-model"}]}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/engines/v1/chat/completions") { exchange ->
            chatRequests.incrementAndGet()
            val body = "data: [DONE]\n\n".toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun modelsWith(retryProperties: DockerRetryProperties = DockerRetryProperties()): Map<String, Any> {
        val registered = mutableMapOf<String, Any>()
        configWith(registered, retryProperties).dockerLocalModelsInitializer()
        return registered
    }

    private fun configWith(
        registered: MutableMap<String, Any>,
        retryProperties: DockerRetryProperties = DockerRetryProperties(),
    ): DockerLocalModelsConfig {
        val beanFactory = mockk<ConfigurableBeanFactory> {
            every { registerSingleton(any(), any()) } answers { registered[firstArg()] = secondArg() }
        }
        return DockerLocalModelsConfig(
            dockerRetryProperties = retryProperties,
            dockerConnectionProperties = DockerConnectionProperties().apply {
                baseUrl = "http://localhost:${server.address.port}/engines"
            },
            configurableBeanFactory = beanFactory,
            properties = ConfigurableModelProviderProperties(defaultEmbeddingModel = "embed-model"),
            observationRegistry = ObjectProviders.empty(),
            localModelDiscoveryProperties = LocalModelDiscoveryProperties(),
        )
    }

    @Nested
    inner class Close {

        @Test
        fun `a streamed call no longer reaches the runner once the configuration is closed`() {
            val registered = mutableMapOf<String, Any>()
            val config = configWith(registered)
            config.dockerLocalModelsInitializer()
            val chatModel = (registered.getValue("dockerModel-chat-model") as SpringAiLlmService).chatModel
            chatModel.stream(Prompt("hello")).collectList().block()

            config.close()
            runCatching { chatModel.stream(Prompt("hello")).collectList().block() }

            assertEquals(1, chatRequests.get())
        }
    }

    @Nested
    inner class ReasoningEffort {

        @Test
        fun `a Docker chat model forwards a configured reasoning effort`() {
            val llm = modelsWith().getValue("dockerModel-chat-model") as SpringAiLlmService
            val options = LlmOptions().withOpenAiReasoningEffort("none")

            assertEquals(
                "none",
                (llm.optionsConverter.convertOptions(options, "chat-model") as OpenAiChatOptions).reasoningEffort,
            )
        }
    }
}
