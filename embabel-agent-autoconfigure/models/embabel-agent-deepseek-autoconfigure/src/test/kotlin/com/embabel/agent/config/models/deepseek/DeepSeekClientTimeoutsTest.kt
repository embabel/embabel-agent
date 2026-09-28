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
package com.embabel.agent.config.models.deepseek

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.ObjectProviders
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeepSeekClientTimeoutsTest {

    private fun bind(vararg properties: Pair<String, String>): DeepSeekProperties =
        Binder(MapConfigurationPropertySource(properties.toMap() + ("embabel.agent.platform.models.deepseek.max-attempts" to "1")))
            .bindOrCreate(DeepSeekProperties.PREFIX, DeepSeekProperties::class.java)

    @Nested
    inner class Binding {

        @Test
        fun `unset timeouts fall back to the http-client ones`() {
            val properties = bind()

            assertNull(properties.connectTimeout)
            assertNull(properties.readTimeout)
        }

        @Test
        fun `timeouts bind under the provider prefix`() {
            val properties = bind(
                "embabel.agent.platform.models.deepseek.connect-timeout" to "5s",
                "embabel.agent.platform.models.deepseek.read-timeout" to "15m",
            )

            assertEquals(Duration.ofSeconds(5), properties.connectTimeout)
            assertEquals(Duration.ofMinutes(15), properties.readTimeout)
        }
    }

    @Nested
    @Timeout(60)
    inner class AgainstASlowServer {

        private lateinit var server: HttpServer
        private val release = CountDownLatch(1)

        @BeforeEach
        fun startServerThatNeverAnswers() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
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

        /** The shared platform client, with the http-client read timeout it would carry: far too long to wait for. */
        private val sharedClientWithLongTimeout: ObjectProvider<RestClient.Builder> =
            StaticListableBeanFactory().apply {
                addBean(
                    "aiModelRestClientBuilder",
                    RestClient.builder().requestFactory(
                        JdkClientHttpRequestFactory().apply { setReadTimeout(Duration.ofMinutes(5)) }
                    ),
                )
            }.getBeanProvider(RestClient.Builder::class.java)

        @Test
        fun `provider read timeout replaces the shared client's`() {
            val llm = llmWith(bind("embabel.agent.platform.models.deepseek.read-timeout" to "300ms"), sharedClientWithLongTimeout)

            assertFailsWithinTenSeconds {
                llm.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
            }
        }

        @Test
        fun `provider read timeout bounds a streamed call`() {
            val llm = llmWith(bind("embabel.agent.platform.models.deepseek.read-timeout" to "300ms"), sharedClientWithLongTimeout)

            assertFailsWithinTenSeconds { llm.chatModel.stream(Prompt("Hi")).blockLast() }
        }

        @Test
        fun `without a provider timeout, the http-client read timeout applies`() {
            val llm = llmWith(bind(), ObjectProviders.empty(), httpReadTimeout = "300ms")

            assertFailsWithinTenSeconds {
                llm.createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
            }
        }

        private fun assertFailsWithinTenSeconds(call: () -> Unit) {
            val started = System.nanoTime()
            assertThrows<Exception> { call() }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(10), "expected failure within 10s but took $elapsed")
        }

        private fun llmWith(
            properties: DeepSeekProperties,
            restClientBuilder: ObjectProvider<RestClient.Builder>,
            httpReadTimeout: String = "5m",
        ): SpringAiLlmService {
            return DeepSeekModelsConfig(
                envBaseUrl = "http://localhost:${server.address.port}",
                envApiKey = "test-key",
                properties = properties,
                observationRegistry = ObjectProviders.empty(),
                restClientBuilderProvider = restClientBuilder,
                webClientBuilderProvider = ObjectProviders.empty(),
                httpReadTimeout = httpReadTimeout,
            ).deepSeekChat()
        }
    }
}
