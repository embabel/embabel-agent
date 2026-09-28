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

import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import com.openai.core.Timeout
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Timeout as TestTimeout
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer
import org.springframework.beans.factory.ObjectProvider
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class OpenAiCompatibleModelFactoryTimeoutTest {

    @Nested
    inner class Defaults {

        @Test
        fun `default client timeout is the one the clients used before it was configurable`() {
            val before = Timeout.builder().request(Duration.ofMinutes(10)).build()
            val now = OpenAiClientTimeouts.DEFAULT.toSdkTimeout()

            assertEquals(before.connect(), now.connect())
            assertEquals(before.read(), now.read())
            assertEquals(before.write(), now.write())
            assertEquals(before.request(), now.request())
        }

        @Test
        fun `default leaves the options converter untouched`() {
            assertSame(OpenAiChatOptionsConverter, OpenAiClientTimeouts.DEFAULT.optionsConverter(OpenAiChatOptionsConverter))
        }

        @Test
        fun `spring ai sends a 60 second timeout with every call unless told otherwise`() {
            val options = OpenAiChatOptionsConverter.convertOptions(LlmOptions(), "any") as OpenAiChatOptions

            assertEquals(Duration.ofSeconds(60), options.timeout)
        }
    }

    @Nested
    inner class Configured {

        private val timeouts = OpenAiClientTimeouts(connect = Duration.ofSeconds(3), read = Duration.ofSeconds(90))

        @Test
        fun `read timeout bounds the whole call as well as each read`() {
            val timeout = timeouts.toSdkTimeout()

            assertEquals(Duration.ofSeconds(3), timeout.connect())
            assertEquals(Duration.ofSeconds(90), timeout.read())
            assertEquals(Duration.ofSeconds(90), timeout.write())
            assertEquals(Duration.ofSeconds(90), timeout.request())
        }

        @Test
        fun `read timeout replaces spring ai's per-call default`() {
            val options = timeouts.optionsConverter(OpenAiChatOptionsConverter)
                .convertOptions(LlmOptions().withTemperature(0.2), "any") as OpenAiChatOptions

            assertEquals(Duration.ofSeconds(90), options.timeout)
            assertEquals(0.2, options.temperature)
            assertEquals("any", options.model)
        }
    }

    @Nested
    @TestTimeout(30)
    inner class AgainstASlowServer {

        private lateinit var server: HttpServer
        private val release = CountDownLatch(1)
        private val requests = AtomicInteger()

        private val shortTimeouts = OpenAiClientTimeouts(read = Duration.ofMillis(300))

        @BeforeEach
        fun startServerThatNeverAnswersInTime() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                release.await(60, TimeUnit.SECONDS)
                exchange.close()
            }
            server.start()
        }

        @AfterEach
        fun stopServer() {
            release.countDown()
            server.stop(0)
        }

        private fun factory(
            customizers: List<OpenAiHttpClientBuilderCustomizer> = emptyList(),
        ) = OpenAiCompatibleModelFactory(
            baseUrl = "http://localhost:${server.address.port}",
            apiKey = "test-key",
            httpClientCustomizers = listProvider(customizers),
            timeouts = shortTimeouts,
        )

        private fun clientExposingFactory(
            customizers: List<OpenAiHttpClientBuilderCustomizer> = emptyList(),
        ) = ClientExposingFactory(
            baseUrl = "http://localhost:${server.address.port}",
            customizers = listProvider(customizers),
            timeouts = shortTimeouts,
        )

        @Test
        fun `embedding client gives up after the configured read timeout`() {
            val embeddingService = factory().openAiCompatibleEmbeddingService(
                model = "slow-embedding",
                provider = "test",
            )

            assertFailsWithin(Duration.ofSeconds(10)) { embeddingService.embed("hello") }
        }

        @Test
        fun `completion client gives up after the configured read timeout`() {
            val llm = factory().openAiCompatibleLlm(
                model = "slow-chat",
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                provider = "test",
                knowledgeCutoffDate = null,
            )

            assertFailsWithin(Duration.ofSeconds(10)) {
                llm.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
            }
        }

        // The calls below carry no Spring AI options, as a Responses API call does not, so only
        // the client-level timeout can end them. Without it they would wait 10 minutes.

        @Test
        fun `client gives up on a call without per-call options`() {
            val client = clientExposingFactory().syncClient()

            assertFailsWithin(Duration.ofSeconds(10)) { client.models().list() }
        }

        @Test
        fun `async client gives up on a call without per-call options`() {
            val client = clientExposingFactory().asyncClient()

            assertFailsWithin(Duration.ofSeconds(10)) { client.models().list().get() }
        }

        @Test
        fun `client built with http customizers gives up on a call without per-call options`() {
            val client = clientExposingFactory(customizers = listOf(OpenAiHttpClientBuilderCustomizer { }))
                .syncClient()

            assertFailsWithin(Duration.ofSeconds(10)) { client.models().list() }
        }

        @Test
        fun `async client built with http customizers gives up on a call without per-call options`() {
            val client = clientExposingFactory(customizers = listOf(OpenAiHttpClientBuilderCustomizer { }))
                .asyncClient()

            assertFailsWithin(Duration.ofSeconds(10)) { client.models().list().get() }
        }

        @Test
        fun `sdk retries a timed out call twice so the caller waits three read timeouts`() {
            val embeddingService = factory().openAiCompatibleEmbeddingService(
                model = "slow-embedding",
                provider = "test",
            )

            assertThrows<Exception> { embeddingService.embed("hello") }

            assertEquals(3, requests.get())
        }

        private fun assertFailsWithin(limit: Duration, call: () -> Unit) {
            val started = System.nanoTime()
            assertThrows<Exception> { call() }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < limit, "expected failure within $limit but took $elapsed")
        }
    }

    private class ClientExposingFactory(
        baseUrl: String,
        customizers: ObjectProvider<OpenAiHttpClientBuilderCustomizer>,
        timeouts: OpenAiClientTimeouts,
    ) : OpenAiCompatibleModelFactory(
        baseUrl = baseUrl,
        apiKey = "test-key",
        httpClientCustomizers = customizers,
        timeouts = timeouts,
    ) {
        fun syncClient(): OpenAIClient = openAiClient

        fun asyncClient(): OpenAIClientAsync = openAiClientAsync
    }

    private fun <T> listProvider(beans: List<T>): ObjectProvider<T> = object : ObjectProvider<T> {
        override fun getObject(): T = beans.first()
        override fun getIfAvailable(): T? = beans.firstOrNull()
        override fun getIfUnique(): T? = beans.singleOrNull()
        override fun iterator(): MutableIterator<T> = beans.toMutableList().iterator()
        override fun orderedStream(): Stream<T> = beans.stream()
        override fun stream(): Stream<T> = beans.stream()
    }
}
