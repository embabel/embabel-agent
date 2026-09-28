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

import com.embabel.agent.openai.OpenAiClientTimeouts
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.util.ObjectProviders
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DockerClientTimeoutsTest {

    private fun bind(vararg properties: Pair<String, String>): DockerRetryProperties =
        Binder(MapConfigurationPropertySource(properties.toMap()))
            .bindOrCreate(DockerRetryProperties.PREFIX, DockerRetryProperties::class.java)

    @Nested
    inner class Binding {

        @Test
        fun `unset timeouts keep today's behaviour`() {
            val properties = bind()

            assertEquals(OpenAiClientTimeouts.DEFAULT_CONNECT, properties.connectTimeout)
            assertNull(properties.readTimeout)
            assertEquals(OpenAiClientTimeouts.DEFAULT, properties.clientTimeouts())
        }

        @Test
        fun `timeouts bind under the docker prefix`() {
            val properties = bind(
                "embabel.agent.platform.models.docker.connect-timeout" to "5s",
                "embabel.agent.platform.models.docker.read-timeout" to "15m",
            )

            assertEquals(
                OpenAiClientTimeouts(connect = Duration.ofSeconds(5), read = Duration.ofMinutes(15)),
                properties.clientTimeouts(),
            )
        }
    }

    @Nested
    @Timeout(30)
    inner class AgainstASlowRunner {

        private lateinit var server: HttpServer
        private val release = CountDownLatch(1)

        @BeforeEach
        fun startRunnerThatNeverFinishesAnEmbedding() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/engines/v1/models") { exchange ->
                val body = """{"object":"list","data":[{"id":"slow-embedding"}]}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.createContext("/engines/v1/embeddings") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                release.await(60, TimeUnit.SECONDS)
                exchange.close()
            }
            server.start()
        }

        @AfterEach
        fun stopRunner() {
            release.countDown()
            server.stop(0)
        }

        @Test
        fun `embedding gives up after the configured read timeout`() {
            val embeddingService = embeddingServiceWith(
                bind("embabel.agent.platform.models.docker.read-timeout" to "300ms"),
            )

            val started = System.nanoTime()
            assertThrows<Exception> { embeddingService.embed("hello") }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)

            assertTrue(elapsed < Duration.ofSeconds(10), "expected failure within 10s but took $elapsed")
        }

        private fun embeddingServiceWith(retryProperties: DockerRetryProperties): EmbeddingService {
            val registered = mutableMapOf<String, Any>()
            val beanFactory = mockk<ConfigurableBeanFactory> {
                every { registerSingleton(any(), any()) } answers { registered[firstArg()] = secondArg() }
            }
            DockerLocalModelsConfig(
                dockerRetryProperties = retryProperties,
                dockerConnectionProperties = DockerConnectionProperties().apply {
                    baseUrl = "http://localhost:${server.address.port}/engines"
                },
                configurableBeanFactory = beanFactory,
                properties = ConfigurableModelProviderProperties(defaultEmbeddingModel = "slow-embedding"),
                observationRegistry = ObjectProviders.empty(),
                localModelDiscoveryProperties = LocalModelDiscoveryProperties(),
            ).dockerLocalModelsInitializer()
            return registered.getValue("dockerModel-slow-embedding") as EmbeddingService
        }
    }
}
