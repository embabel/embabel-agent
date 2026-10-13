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
package com.embabel.agent.systemone

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.classification.CategoryProbability
import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.byok.InvalidApiKeyException
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class SystemOneModelFactoryTest {

    private val base = "http://systemone.example/base"
    private val endpoint = SystemOneEndpoint("Example", URI.create(base), requestIdHeader = "x-request-id")
    private val urgent = Questions.named("urgent").proposition("Is this urgent?").build()
    private val noulBody = """{"model":"model-1b","answers":{"q1":{"type":"noul","noul":0.9}}}"""
    private val mapper = JsonMapper.builder().build()

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(SystemOneDecisionService::class.java) as Logger

    @BeforeEach
    fun captureLogs() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun releaseLogs() {
        logger.detachAppender(appender)
    }

    private class Mocked(val factory: SystemOneModelFactory, val server: MockRestServiceServer)

    private fun mocked(
        key: Supplier<String?> = Supplier { "test-key" },
        registry: ObservationRegistry = ObservationRegistry.NOOP,
        endpoint: SystemOneEndpoint = this.endpoint,
    ): Mocked {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return Mocked(SystemOneModelFactory(endpoint, "model-1", key, builder, registry), server)
    }

    private fun ask(factory: SystemOneModelFactory, input: String = "charged twice"): DecisionResponse =
        factory.build().ask(DecisionRequest.of(input, urgent))

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    /** A body that counts how many bytes were read from it. */
    private class CountingStream(size: Int) : InputStream() {
        private val delegate = ByteArrayInputStream(ByteArray(size) { 'x'.code.toByte() })
        var read = 0
        override fun read(): Int = delegate.read().also { if (it >= 0) read++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { if (it > 0) read += it }
    }

    @Nested
    inner class Requests {

        @Test
        fun `posts the encoded question set under the base path with the key as a bearer token`() {
            val mocked = mocked()
            var body: String? = null
            var headers: HttpHeaders? = null
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    body = (request as MockClientHttpRequest).bodyAsString
                    headers = request.headers
                }
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

            ask(mocked.factory)

            mocked.server.verify()
            assertEquals(
                mapper.readTree("""{"state":"charged twice","model":"model-1","questions":{"q1":{"type":"noul","instructions":"Is this urgent?"}}}"""),
                mapper.readTree(body),
            )
            assertEquals("Bearer test-key", headers!!.getFirst(HttpHeaders.AUTHORIZATION))
            assertEquals(MediaType.APPLICATION_JSON, headers!!.contentType)
        }

        @Test
        fun `a blank key sends no authorization header`() {
            val mocked = mocked(key = { " " })
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andExpect { assertNull(it.headers.getFirst(HttpHeaders.AUTHORIZATION)) }
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

            ask(mocked.factory)

            mocked.server.verify()
        }

        @Test
        fun `the key is resolved on every request and never when building`() {
            val calls = AtomicInteger()
            val mocked = mocked(key = { "key-${calls.incrementAndGet()}" })
            mocked.server.expect(ExpectedCount.twice(), requestTo("$base/v1/systemone"))
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

            val service = mocked.factory.build()
            assertEquals(0, calls.get())
            service.ask(DecisionRequest.of("a", urgent))
            service.ask(DecisionRequest.of("b", urgent))

            assertEquals(2, calls.get())
        }

        @Test
        fun `a failing or unsafe key fails the request without calling the endpoint`() {
            listOf<Supplier<String?>>(Supplier { error("vault down") }, Supplier { "key\r\nX-Injected: 1" }).forEach { key ->
                val mocked = mocked(key = key)

                val response = ask(mocked.factory)

                assertEquals(FailureReason.UNAVAILABLE, response.requestFailure)
                mocked.server.verify()
            }
            assertTrue(warnings().all { it.contains("cause=credential") }, warnings().toString())
        }

        @Test
        fun `a spec with every question kind goes out as one request`() {
            val mocked = mocked()
            val team = Questions.named("team").choice("Which team?").option("billing", "Payments").option("sales", "Sales").build()
            val anger = Questions.named("anger").rating("How angry?").level("calm").level("furious").build()
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(
                withSuccess(
                    """{"answers":{"q1":{"type":"noul","noul":0.9},""" +
                        """"q2":{"type":"choice","choice":"billing","probabilities":{"billing":0.75,"sales":0.25},"confidence":0.3},""" +
                        """"q3":{"type":"score","score":0.5,"legend":{"0":"calm","1":"furious"},"probabilities":{"0":0.5,"1":0.5},"confidence":0.1}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

            val response = mocked.factory.build().ask(DecisionRequest.of("charged twice", urgent, team, anger))

            mocked.server.verify()
            assertNull(response.requestFailure)
            assertEquals(QuestionKind.entries.toSet(), mocked.factory.build().capabilities().questionKinds)
        }

        @Test
        fun `the per question hooks send one question each`() {
            val mocked = mocked()
            val anger = Questions.named("anger").rating("How angry?").level("calm").level("furious").build()
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(
                withSuccess(
                    """{"answers":{"q1":{"type":"score","score":1.0,"legend":{"0":"calm","1":"furious"},"probabilities":{"0":0.0,"1":1.0},"confidence":1.0}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
            val service = mocked.factory.build()

            val assessed = (service as PropositionAssessment).assess("charged twice", urgent)
            val rated = (service as RatingAssessment).rate("charged twice", anger)

            mocked.server.verify()
            assertTrue(assessed is PropositionResult.Answered)
            assertTrue(rated is RatingResult.Answered)
        }

        @Test
        fun `classify and assess each send one question`() {
            val mocked = mocked()
            val spec = ClassificationSpec.builder().asking("Which team?")
                .category("billing", "Payments").category("technical", "Outages").build()
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andRespond(
                    withSuccess(
                        """{"answers":{"q1":{"type":"choice","choice":"billing","probabilities":{"billing":0.75,"technical":0.25},"confidence":0.3}}}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

            val service = mocked.factory.build()
            val classified = service.classify(ClassificationRequest.of("charged twice", spec))
            val assessed = service.assess(PropositionRequest("charged twice", "Is this urgent?"))

            assertEquals(
                ClassificationResult.Selected(
                    "billing", ModelProvenance("model-1", "Example"), 0.3,
                    listOf(CategoryProbability("billing", 0.75), CategoryProbability("technical", 0.25)),
                ),
                classified,
            )
            assertEquals(PropositionResult.Answered(true, ModelProvenance("model-1b", "Example"), 0.9), assessed)
        }
    }

    @Nested
    inner class Responses {

        @Test
        fun `provenance names the answering model, the provider and a well formed request id`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON).header("x-request-id", "req-42"))
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON).header("x-request-id", "<script>"))

            val first = ask(mocked.factory).answer(urgent) as PropositionResult.Answered
            val second = ask(mocked.factory).answer(urgent) as PropositionResult.Answered

            assertEquals(ModelProvenance("model-1b", "Example", null, "req-42"), first.provenance)
            assertNull(second.provenance.requestId)
        }

        @Test
        fun `an error status fails the whole request as unavailable without reading the body`() {
            val mocked = mocked()
            val body = CountingStream(4096)
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andRespond { MockClientHttpResponse(body, HttpStatus.SERVICE_UNAVAILABLE) }

            val response = ask(mocked.factory)

            assertEquals(FailureReason.UNAVAILABLE, response.requestFailure)
            assertEquals(0, body.read)
            assertEquals(1, warnings().size)
            assertTrue(warnings().single().contains("cause=http_5xx, status=5xx"), warnings().single())
        }

        @Test
        fun `rate limiting is reported as its own cause`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

            assertEquals(FailureReason.UNAVAILABLE, ask(mocked.factory).requestFailure)
            assertTrue(warnings().single().contains("cause=rate_limited"), warnings().single())
        }

        @Test
        fun `an unreadable body fails the whole request as an invalid response`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withSuccess("SENTINEL", MediaType.APPLICATION_JSON))

            assertEquals(FailureReason.INVALID_RESPONSE, ask(mocked.factory).requestFailure)
            assertTrue(warnings().single().contains("cause=invalid_response"), warnings().single())
        }

        @Test
        fun `a body over the limit is rejected from its length or after reading one byte past it`() {
            val small = SystemOneEndpoint("Example", URI.create(base), maxResponseBytes = 64)
            val declared = mocked(endpoint = small)
            val declaredBody = CountingStream(1000)
            declared.server.expect(requestTo("$base/v1/systemone")).andRespond {
                MockClientHttpResponse(declaredBody, HttpStatus.OK).apply { headers.contentLength = 1000 }
            }
            val streamed = mocked(endpoint = small)
            val streamedBody = CountingStream(1000)
            streamed.server.expect(requestTo("$base/v1/systemone")).andRespond { MockClientHttpResponse(streamedBody, HttpStatus.OK) }

            assertEquals(FailureReason.INVALID_RESPONSE, ask(declared.factory).requestFailure)
            assertEquals(FailureReason.INVALID_RESPONSE, ask(streamed.factory).requestFailure)
            assertEquals(0, declaredBody.read)
            assertEquals(65, streamedBody.read)
        }

        @Test
        fun `failure lines hold no input, key, body or endpoint`() {
            val mocked = mocked(key = { "SECRET-KEY" })
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("SENTINEL-BODY"))

            ask(mocked.factory, input = "SENTINEL-INPUT")

            val line = warnings().single()
            listOf("SENTINEL", "SECRET-KEY", "systemone.example").forEach { assertFalse(line.contains(it), line) }
            assertTrue(line.startsWith("System One question set failed with reason UNAVAILABLE"), line)
        }
    }

    @Nested
    inner class Observations {

        private val stopped = mutableListOf<Observation.Context>()
        private val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStop(context: Observation.Context) {
                    stopped += context
                }
            })
        }

        private fun tags() = stopped.filter { it.name == "embabel.systemone.request" }
            .map { context -> context.lowCardinalityKeyValues.associate { it.key to it.value } }

        @Test
        fun `each call is observed with fixed low cardinality tags`() {
            val mocked = mocked(registry = registry)
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withStatus(HttpStatus.BAD_GATEWAY))

            ask(mocked.factory)
            ask(mocked.factory)

            assertEquals(
                listOf(
                    mapOf("provider" to "Example", "operation" to "systemone", "outcome" to "success", "status.family" to "2xx"),
                    mapOf("provider" to "Example", "operation" to "systemone", "outcome" to "failure", "status.family" to "5xx"),
                ),
                tags(),
            )
            assertTrue(stopped.flatMap { it.highCardinalityKeyValues }.isEmpty())
        }
    }

    @Nested
    inner class Interruption {

        @Test
        fun `an interrupted caller is cancelled before any request and keeps its flag`() {
            val calls = AtomicInteger()
            val mocked = mocked(key = { "k".also { calls.incrementAndGet() } })

            Thread.currentThread().interrupt()
            val e = try {
                assertThrows<CancellationException> { ask(mocked.factory) }
            } finally {
                assertTrue(Thread.interrupted())
            }

            assertTrue(e.cause is InterruptedException)
            assertEquals(0, calls.get())
            mocked.server.verify()
        }
    }

    @Nested
    inner class Byok {

        @Test
        fun `a blank key is invalid without calling the endpoint`() {
            val mocked = mocked(key = { "" })

            assertThrows<InvalidApiKeyException> { mocked.factory.buildValidated() }
            mocked.server.verify()
        }

        @Test
        fun `a rejected listing makes the key invalid`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/v1/models")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

            val e = assertThrows<InvalidApiKeyException> { mocked.factory.buildValidated() }
            assertFalse(e.message!!.contains("test-key"))
        }

        @Test
        fun `a successful listing returns the default model`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""{"models":[{"name":"model-1"}]}""", MediaType.APPLICATION_JSON))

            val service = mocked.factory.buildValidated()

            assertEquals("model-1", service.name)
            assertEquals("Example", service.provider)
        }

        @Test
        fun `model names come from the listing`() {
            val mocked = mocked()
            mocked.server.expect(requestTo("$base/v1/models"))
                .andRespond(withSuccess("""{"models":[{"name":"model-1"},{"name":"model-2"}]}""", MediaType.APPLICATION_JSON))

            assertEquals(listOf("model-1", "model-2"), mocked.factory.modelNames())
        }
    }

    @Nested
    inner class RealSockets {

        private lateinit var server: HttpServer
        private val redirected = AtomicInteger()

        @BeforeEach
        fun start() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/models") { exchange ->
                exchange.responseHeaders.add("Location", "/elsewhere")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/elsewhere") { exchange ->
                redirected.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.createContext("/v1/systemone") { exchange ->
                exchange.requestBody.readAllBytes()
                if (exchange.requestHeaders.getFirst("X-Slow") == null) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { out -> repeat(64) { out.write(ByteArray(1024)) } }
                } else {
                    Thread.sleep(2000)
                    exchange.close()
                }
            }
            server.start()
        }

        @AfterEach
        fun stop() {
            server.stop(0)
        }

        private fun factory(readTimeout: Duration = Duration.ofSeconds(5), port: Int = server.address.port) =
            SystemOneModelFactory(
                SystemOneEndpoint("Example", URI.create("http://127.0.0.1:$port"), readTimeout = readTimeout, maxResponseBytes = 1024),
                "model-1",
                { "k" },
            )

        @Test
        fun `the fallback transport does not follow redirects`() {
            assertThrows<IllegalStateException> { factory().modelNames() }
            assertEquals(0, redirected.get())
        }

        @Test
        fun `a streamed body over the limit is an invalid response`() {
            assertEquals(FailureReason.INVALID_RESPONSE, ask(factory()).requestFailure)
        }

        @Test
        fun `a read timeout is unavailable`() {
            val builder = RestClient.builder().defaultHeader("X-Slow", "1")
            val slow = SystemOneModelFactory(
                SystemOneEndpoint("Example", URI.create("http://127.0.0.1:${server.address.port}"), readTimeout = Duration.ofMillis(200)),
                "model-1",
                { "k" },
                builder.requestFactory(
                    org.springframework.http.client.SimpleClientHttpRequestFactory().apply { setReadTimeout(200) },
                ),
            )

            assertEquals(FailureReason.UNAVAILABLE, ask(slow).requestFailure)
            assertTrue(warnings().single().contains("cause=timeout"), warnings().single())
        }

        @Test
        fun `a refused connection is unavailable`() {
            val port = ServerSocket(0).use { it.localPort }

            assertEquals(FailureReason.UNAVAILABLE, ask(factory(port = port)).requestFailure)
            assertTrue(warnings().single().contains("cause=connection"), warnings().single())
        }
    }
}
