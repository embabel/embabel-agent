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
package com.embabel.agent.config.models.mistralai

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.ObjectProviders
import com.sun.net.httpserver.HttpServer
import io.netty.channel.ConnectTimeoutException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.ReactorClientHttpRequestFactory
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MistralAiClientTimeoutsTest {

    private fun bind(vararg properties: Pair<String, String>): MistralAiProperties =
        Binder(MapConfigurationPropertySource(properties.toMap() + ("embabel.agent.platform.models.mistralai.max-attempts" to "1")))
            .bindOrCreate(MistralAiProperties.PREFIX, MistralAiProperties::class.java)

    /** The shared platform clients, carrying a read timeout far too long to wait for, as NettyClientAutoConfiguration builds them. */
    private val sharedHttpClient: HttpClient = HttpClient.create().followRedirect(true).responseTimeout(Duration.ofMinutes(5))

    private val sharedRestClient: ObjectProvider<RestClient.Builder> =
        StaticListableBeanFactory().apply {
            addBean("aiModelRestClientBuilder", RestClient.builder().requestFactory(ReactorClientHttpRequestFactory(sharedHttpClient)))
        }.getBeanProvider(RestClient.Builder::class.java)

    private val sharedWebClient: ObjectProvider<WebClient.Builder> =
        StaticListableBeanFactory().apply {
            addBean("aiModelWebClientBuilder", WebClient.builder().clientConnector(ReactorClientHttpConnector(sharedHttpClient)))
        }.getBeanProvider(WebClient.Builder::class.java)

    private fun llmWith(
        baseUrl: String,
        properties: MistralAiProperties,
        restClientBuilder: ObjectProvider<RestClient.Builder> = sharedRestClient,
        webClientBuilder: ObjectProvider<WebClient.Builder> = sharedWebClient,
        httpReadTimeout: String = "5m",
        httpConnectTimeout: String = "25s",
        httpUseReactorNetty: String = "true",
        inspect: (MistralAiModelsConfig) -> Unit = {},
    ): SpringAiLlmService {
        val beanFactory = DefaultListableBeanFactory()
        val registered = MistralAiModelsConfig(
            envBaseUrl = baseUrl,
            envApiKey = "test-key",
            properties = properties,
            observationRegistry = ObjectProviders.empty(),
            configurableBeanFactory = beanFactory,
            restClientBuilderProvider = restClientBuilder,
            webClientBuilderProvider = webClientBuilder,
            httpReadTimeout = httpReadTimeout,
            httpConnectTimeout = httpConnectTimeout,
            httpUseReactorNetty = httpUseReactorNetty,
        ).also(inspect).mistralAiModelsInitializer().registeredLlms.first()
        return beanFactory.getBean(registered.beanName) as SpringAiLlmService
    }

    private fun SpringAiLlmService.callOnce() {
        createMessageSender(LlmOptions()).call(listOf(UserMessage("Hi")), emptyList())
    }

    private fun assertFailsWithinTenSeconds(call: () -> Unit): Throwable {
        val started = System.nanoTime()
        val failure = assertThrows<Exception> { call() }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed < Duration.ofSeconds(10), "expected failure within 10s but took $elapsed")
        return failure
    }

    @Nested
    inner class Binding {

        @Test
        fun `unset timeouts bind as null`() {
            val properties = bind()

            assertNull(properties.connectTimeout)
            assertNull(properties.readTimeout)
        }

        @Test
        fun `timeouts bind under the provider prefix`() {
            val properties = bind(
                "embabel.agent.platform.models.mistralai.connect-timeout" to "5s",
                "embabel.agent.platform.models.mistralai.read-timeout" to "15m",
            )

            assertEquals(Duration.ofSeconds(5), properties.connectTimeout)
            assertEquals(Duration.ofMinutes(15), properties.readTimeout)
        }

        @Test
        fun `a malformed http-client timeout names the property`() {
            val failure = assertThrows<IllegalArgumentException> {
                llmWith("http://localhost:1", bind(), httpReadTimeout = "soon")
            }

            assertTrue(
                failure.message.orEmpty().contains("embabel.agent.platform.http-client.read-timeout"),
                "message should name the property: ${failure.message}",
            )
        }
    }

    @Nested
    @Timeout(60)
    inner class AgainstASlowServer {

        private lateinit var server: HttpServer
        private val release = CountDownLatch(1)
        private val baseUrl get() = "http://localhost:${server.address.port}"

        @BeforeEach
        fun startServer() {
            server = HttpServer.create(InetSocketAddress(0), 0)
            server.executor = Executors.newCachedThreadPool()
            // Never answers.
            server.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                release.await(60, TimeUnit.SECONDS)
                exchange.close()
            }
            // Answers a streamed request with headers and one event, then stalls mid-body.
            server.createContext("/stall/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(FIRST_CHUNK.toByteArray())
                exchange.responseBody.flush()
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

        @Test
        fun `provider read timeout replaces the shared client's`() {
            val llm = llmWith(baseUrl, bind("embabel.agent.platform.models.mistralai.read-timeout" to "300ms"))

            assertFailsWithinTenSeconds { llm.callOnce() }
        }

        @Test
        fun `provider read timeout bounds a streamed call that never answers`() {
            val llm = llmWith(baseUrl, bind("embabel.agent.platform.models.mistralai.read-timeout" to "300ms"))

            assertFailsWithinTenSeconds { llm.chatModel.stream(Prompt("Hi")).blockLast() }
        }

        @Test
        fun `provider read timeout bounds a stream that stalls after its first event`() {
            val llm = llmWith("$baseUrl/stall", bind("embabel.agent.platform.models.mistralai.read-timeout" to "300ms"))
            val received = AtomicInteger()

            assertFailsWithinTenSeconds {
                llm.chatModel.stream(Prompt("Hi")).doOnNext { received.incrementAndGet() }.blockLast()
            }
            assertTrue(received.get() > 0, "the first event should arrive before the stream stalls")
        }

        @Test
        fun `a provider connect timeout alone takes the read timeout from http-client`() {
            val llm = llmWith(
                baseUrl,
                bind("embabel.agent.platform.models.mistralai.connect-timeout" to "5s"),
                httpReadTimeout = "300ms",
            )

            assertFailsWithinTenSeconds { llm.callOnce() }
        }

        @Test
        fun `with reactor-netty opted out, a provider timeout uses JDK clients that still bound a call`() {
            val llm = llmWith(
                baseUrl,
                bind("embabel.agent.platform.models.mistralai.read-timeout" to "300ms"),
                restClientBuilder = ObjectProviders.empty(),
                webClientBuilder = ObjectProviders.empty(),
                httpUseReactorNetty = "false",
            ) { config ->
                assertTrue(config.timeouts.requestFactory() is JdkClientHttpRequestFactory)
                assertTrue(config.timeouts.connector() is JdkClientHttpConnector)
            }

            assertFailsWithinTenSeconds { llm.callOnce() }
        }

        @Test
        fun `without a provider timeout, the http-client read timeout applies`() {
            val llm = llmWith(baseUrl, bind(), restClientBuilder = ObjectProviders.empty(), httpReadTimeout = "300ms")

            assertFailsWithinTenSeconds { llm.callOnce() }
        }
    }

    @Nested
    @Timeout(60)
    inner class AgainstAnAddressThatNeverAnswers {

        /**
         * Skips the test unless a connection to [BLACKHOLE] goes unanswered here. Where the network
         * refuses it or reports it unreachable instead, no connect timeout can be observed.
         */
        private fun assumeBlackholed() {
            val unanswered = Socket().use { socket ->
                try {
                    socket.connect(InetSocketAddress(BLACKHOLE, 80), 200)
                    false
                } catch (e: SocketTimeoutException) {
                    true
                } catch (e: IOException) {
                    false
                }
            }
            assumeTrue(unanswered, "$BLACKHOLE does not blackhole connections on this network")
        }

        @Test
        fun `provider connect timeout bounds a connection that is never answered`() {
            assumeBlackholed()
            val llm = llmWith(
                "http://$BLACKHOLE",
                bind("embabel.agent.platform.models.mistralai.connect-timeout" to "300ms"),
            )

            val failure = assertFailsWithinTenSeconds { llm.callOnce() }

            assertTrue(
                generateSequence(failure, Throwable::cause).any { it is ConnectTimeoutException },
                "expected a connect timeout, got $failure",
            )
        }
    }

    private companion object {
        /** TEST-NET-1 (RFC 5737): reserved for documentation, never assigned, so normally dropped. */
        const val BLACKHOLE = "192.0.2.1"

        const val FIRST_CHUNK =
            """data: {"id":"1","object":"chat.completion.chunk","created":0,"model":"m",""" +
                """"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}""" +
                "\n\n"
    }
}
