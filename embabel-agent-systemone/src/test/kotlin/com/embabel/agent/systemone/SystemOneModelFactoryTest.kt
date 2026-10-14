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
import com.embabel.common.ai.decision.systemone.SystemOneCodec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.systemone.SystemOneFormatException
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
import org.junit.jupiter.api.assertDoesNotThrow
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
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        builder: RestClient.Builder = RestClient.builder(),
    ): Mocked {
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

    private class BoundedCyclicFailure : RuntimeException("SENTINEL") {
        lateinit var next: Throwable
        private var reads = 0
        override val cause: Throwable
            get() {
                // Fail the old traversal promptly instead of leaving the test stuck in its cycle.
                if (++reads > 16) throw AssertionError("Cause cycle was traversed repeatedly")
                return next
            }
    }

    private fun cyclicFailure(nested: Throwable = IllegalStateException("SENTINEL")): RuntimeException =
        BoundedCyclicFailure().also {
            it.next = nested
            nested.initCause(it)
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
        fun `a blank key also drops an authorization header the application client would add`() {
            val builder = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer application-secret")
                .defaultRequest { it.header(HttpHeaders.AUTHORIZATION, "Bearer request-secret") }
            val mocked = mocked(key = { "" }, builder = builder)
            mocked.server.expect(requestTo("$base/v1/systemone"))
                .andExpect { assertNull(it.headers.getFirst(HttpHeaders.AUTHORIZATION)) }
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

            ask(mocked.factory)

            mocked.server.verify()
        }

        @Test
        fun `the key wins over authorization set by the application's initializers and interceptors`() {
            fun builder() = RestClient.builder()
                .requestInitializer { it.headers.setBearerAuth("initializer-secret") }
                .requestInterceptor { request, body, execution ->
                    request.headers.setBearerAuth("interceptor-secret")
                    execution.execute(request, body)
                }
            val anonymous = mocked(key = { "" }, builder = builder())
            anonymous.server.expect(requestTo("$base/v1/systemone"))
                .andExpect { assertNull(it.headers.getFirst(HttpHeaders.AUTHORIZATION)) }
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))
            val keyed = mocked(builder = builder())
            keyed.server.expect(requestTo("$base/v1/systemone"))
                .andExpect { assertEquals(listOf("Bearer test-key"), it.headers[HttpHeaders.AUTHORIZATION]) }
                .andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

            ask(anonymous.factory)
            ask(keyed.factory)

            anonymous.server.verify()
            keyed.server.verify()
        }

        @Test
        fun `a key supplier that throws InterruptedException itself cancels the call and validation`() {
            val mocked = mocked(key = { throw InterruptedException() })

            listOf<() -> Any>({ ask(mocked.factory) }, { mocked.factory.buildValidated() }).forEach { call ->
                try {
                    assertThrows<CancellationException> { call() }
                } finally {
                    assertTrue(Thread.interrupted())
                }
            }
            mocked.server.verify()
        }

        @Test
        fun `a key supplier interrupted while it blocks cancels the call`() {
            val mocked = mocked(key = { throw IllegalStateException(InterruptedException()) })

            val e = try {
                assertThrows<CancellationException> { ask(mocked.factory) }
            } finally {
                assertTrue(Thread.interrupted())
            }

            assertTrue(e.cause is InterruptedException)
            mocked.server.verify()
        }

        @Test
        fun `a cyclic credential failure is unavailable without traversing forever`() {
            val failure = cyclicFailure()
            val mocked = mocked(key = { throw failure })

            assertEquals(FailureReason.UNAVAILABLE, ask(mocked.factory).requestFailure)
            assertTrue(warnings().single().contains("cause=credential"), warnings().single())
            assertFalse(warnings().single().contains("SENTINEL"), warnings().single())
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
        fun `cyclic client failures terminate and retain timeout classification`() {
            listOf(
                Triple(IllegalStateException("SENTINEL"), FailureReason.INVALID_RESPONSE, "other"),
                Triple(SocketTimeoutException("SENTINEL"), FailureReason.UNAVAILABLE, "timeout"),
            ).forEach { (nested, reason, category) ->
                val failure = cyclicFailure(nested)
                val mocked = mocked()
                mocked.server.expect(requestTo("$base/v1/systemone")).andRespond { throw failure }

                assertEquals(reason, ask(mocked.factory).requestFailure)
                assertTrue(warnings().last().contains("cause=$category"), warnings().last())
                assertFalse(warnings().last().contains("SENTINEL"), warnings().last())
                mocked.server.verify()
            }
        }

        @Test
        fun `wrapped timeout families take precedence over connection failures`() {
            listOf(
                SocketTimeoutException("SENTINEL"),
                HttpTimeoutException("SENTINEL"),
                TimeoutException("SENTINEL"),
            ).forEach { cause ->
                val mocked = mocked()
                mocked.server.expect(requestTo("$base/v1/systemone")).andRespond {
                    throw ResourceAccessException("SENTINEL", IOException(cause))
                }
                assertEquals(FailureReason.UNAVAILABLE, ask(mocked.factory).requestFailure)
                assertTrue(warnings().last().contains("cause=timeout"), warnings().last())
                assertFalse(warnings().last().contains("SENTINEL"), warnings().last())
                mocked.server.verify()
            }
        }

        @Test
        fun `other client exceptions use connection invalid response or other categories`() {
            listOf(
                Triple(ResourceAccessException("SENTINEL"), FailureReason.UNAVAILABLE, "connection"),
                Triple(RestClientException("SENTINEL"), FailureReason.INVALID_RESPONSE, "invalid_response"),
                Triple(IllegalStateException("SENTINEL"), FailureReason.INVALID_RESPONSE, "other"),
            ).forEach { (exception, reason, category) ->
                val mocked = mocked()
                mocked.server.expect(requestTo("$base/v1/systemone")).andRespond { throw exception }
                assertEquals(reason, ask(mocked.factory).requestFailure)
                assertTrue(warnings().last().contains("cause=$category"), warnings().last())
                assertFalse(warnings().last().contains("SENTINEL"), warnings().last())
                mocked.server.verify()
            }
        }

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
        fun `an application client that buffers responses still stops at the limit and skips error bodies`() {
            val small = SystemOneEndpoint("Example", URI.create(base), maxResponseBytes = 64)
            val streamed = mocked(endpoint = small, builder = RestClient.builder().bufferContent { _, _ -> true })
            val streamedBody = CountingStream(1000)
            streamed.server.expect(requestTo("$base/v1/systemone")).andRespond { MockClientHttpResponse(streamedBody, HttpStatus.OK) }
            val failed = mocked(builder = RestClient.builder().bufferContent { _, _ -> true })
            val errorBody = CountingStream(1000)
            failed.server.expect(requestTo("$base/v1/systemone"))
                .andRespond { MockClientHttpResponse(errorBody, HttpStatus.SERVICE_UNAVAILABLE) }

            assertEquals(FailureReason.INVALID_RESPONSE, ask(streamed.factory).requestFailure)
            assertEquals(FailureReason.UNAVAILABLE, ask(failed.factory).requestFailure)
            assertEquals(65, streamedBody.read)
            assertEquals(0, errorBody.read)
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
        fun `a failing observation handler changes neither the result nor the current scope`() {
            val broken = ObservationRegistry.create().apply {
                observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context) = context.name == "embabel.systemone.request"
                    override fun onScopeOpened(context: Observation.Context): Unit = error("handler broke")
                    override fun onStop(context: Observation.Context): Unit = error("handler broke")
                })
            }
            val mocked = mocked(registry = broken)
            mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))
            mocked.server.expect(requestTo("$base/v1/models"))
                .andRespond(withSuccess("""{"models":[{"name":"model-1"}]}""", MediaType.APPLICATION_JSON))

            assertNull(ask(mocked.factory).requestFailure)
            assertEquals(listOf("model-1"), mocked.factory.modelNames())
            assertNull(broken.currentObservationScope)
        }

        @Test
        fun `a handler that fails part way through still lets earlier handlers clean up`() {
            val events = mutableListOf<String>()
            val recording = object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = context.name == "embabel.systemone.request"
                override fun onStart(context: Observation.Context) {
                    events += "start"
                }
                override fun onStop(context: Observation.Context) {
                    events += "stop"
                }
                override fun onScopeOpened(context: Observation.Context) {
                    events += "open"
                }
                override fun onScopeClosed(context: Observation.Context) {
                    events += "close"
                }
            }
            fun registry(broken: ObservationHandler<Observation.Context>) = ObservationRegistry.create().apply {
                observationConfig().observationHandler(recording).observationHandler(broken)
            }
            val failsToOpen = object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onScopeOpened(context: Observation.Context): Unit = error("handler broke")
            }
            val failsToStart = object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStart(context: Observation.Context): Unit = error("handler broke")
            }
            listOf(failsToOpen, failsToStart).forEach { broken ->
                events.clear()
                val mocked = mocked(registry = registry(broken))
                mocked.server.expect(requestTo("$base/v1/systemone")).andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

                assertNull(ask(mocked.factory).requestFailure)

                assertEquals(events.count { it == "open" }, events.count { it == "close" }, events.toString())
                assertEquals(events.count { it == "start" }, events.count { it == "stop" }, events.toString())
            }
        }

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
    inner class Bindings {

        // Wraps the protocol the way a hosting API might: the model in the path, a body of its own and an envelope.
        private val hosted = object : SystemOneBinding {
            override fun decisionUri(endpoint: SystemOneEndpoint, model: String): URI = endpoint.resolve("/run/$model")
            override fun decisionRequest(model: String, request: DecisionRequest): ByteArray =
                """{"model":"${model.substringAfterLast('/')}","input":${SystemOneCodec.encode(model, request).decodeToString()}}""".encodeToByteArray()
            override fun check(model: String, request: DecisionRequest) {
                if (model == "acme/small" && request.spec.questions.size > 1) throw UnsupportedDecisionException("One question at a time")
            }
            override fun decisionResponse(body: ByteArray): ByteArray {
                val text = body.decodeToString()
                if (!text.startsWith("[")) throw SystemOneFormatException("No envelope")
                return text.removePrefix("[").removeSuffix("]").encodeToByteArray()
            }
            override fun modelsUri(endpoint: SystemOneEndpoint): URI = endpoint.resolve("/catalog")
            override fun modelNames(body: ByteArray) = body.decodeToString().split(',')
        }

        private fun hosted(key: Supplier<String?> = Supplier { "test-key" }): Mocked {
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            val endpoint = SystemOneEndpoint("Hosted", URI.create(base), binding = hosted)
            return Mocked(SystemOneModelFactory(endpoint, "acme/small", key, builder), server)
        }

        @Test
        fun `the binding picks the address, the body and the envelope`() {
            val mocked = hosted()
            var body: String? = null
            mocked.server.expect(requestTo("$base/run/acme/small"))
                .andExpect { body = (it as MockClientHttpRequest).bodyAsString }
                .andRespond(withSuccess("[$noulBody]", MediaType.APPLICATION_JSON))

            val answer = ask(mocked.factory).answer(urgent)

            assertEquals("small", mapper.readTree(body).get("model").stringValue())
            assertEquals("noul", mapper.readTree(body).get("input").get("questions").get("q1").get("type").stringValue())
            assertEquals(PropositionResult.Answered(true, ModelProvenance("model-1b", "Hosted"), 0.9), answer)
        }

        @Test
        fun `a body the binding can't unwrap is an invalid response`() {
            val mocked = hosted()
            mocked.server.expect(requestTo("$base/run/acme/small")).andRespond(withSuccess(noulBody, MediaType.APPLICATION_JSON))

            assertEquals(FailureReason.INVALID_RESPONSE, ask(mocked.factory).requestFailure)
        }

        @Test
        fun `captured content holds only the unwrapped response`() {
            val mocked = hosted()
            mocked.server.expect(requestTo("$base/run/acme/small"))
                .andRespond(withSuccess("""{"error":"SENTINEL"}""", MediaType.APPLICATION_JSON))
            val level = logger.level
            logger.level = Level.TRACE
            DecisionContentCapture.enable()
            try {
                assertEquals(FailureReason.INVALID_RESPONSE, ask(mocked.factory).requestFailure)
            } finally {
                DecisionContentCapture.disable()
                logger.level = level
            }

            assertTrue(appender.list.none { it.formattedMessage.contains("SENTINEL") })
        }

        @Test
        fun `a request the binding refuses throws before the key is read`() {
            val calls = AtomicInteger()
            val mocked = hosted(key = { "k".also { calls.incrementAndGet() } })
            val team = Questions.named("team").choice("Which team?").option("billing", "Payments").option("sales", "Sales").build()

            assertThrows<UnsupportedDecisionException> {
                mocked.factory.build().ask(DecisionRequest.of("charged twice", urgent, team))
            }

            assertEquals(0, calls.get())
            mocked.server.verify()
        }

        @Test
        fun `the binding checks each request against the model it goes to`() {
            val mocked = hosted()
            val team = Questions.named("team").choice("Which team?").option("billing", "Payments").option("sales", "Sales").build()
            mocked.server.expect(requestTo("$base/run/acme/large")).andRespond(withSuccess("[$noulBody]", MediaType.APPLICATION_JSON))

            mocked.factory.build("acme/large").ask(DecisionRequest.of("charged twice", urgent, team))

            mocked.server.verify()
        }

        @Test
        fun `a model the binding can never serve fails the build and sends nothing`() {
            val calls = AtomicInteger()
            val strict = object : SystemOneBinding {
                override fun validateModel(model: String) {
                    require(model.startsWith("acme/")) { "An Acme model id looks like acme/<model>" }
                }
            }
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            val endpoint = SystemOneEndpoint("Hosted", URI.create(base), binding = strict)
            val factory = SystemOneModelFactory(endpoint, "acme/small", { "k".also { calls.incrementAndGet() } }, builder)

            val e = assertThrows<IllegalArgumentException> { factory.build("other/model") }

            assertEquals("An Acme model id looks like acme/<model>", e.message)
            assertEquals("acme/small", factory.build().name)
            assertEquals(0, calls.get())
            server.verify()
        }

        @Test
        fun `the standard binding accepts any model name`() {
            assertDoesNotThrow { SystemOneBinding.STANDARD.validateModel("anything/at all") }
        }

        @Test
        fun `model listing and key validation use the binding's listing`() {
            val mocked = hosted()
            mocked.server.expect(ExpectedCount.twice(), requestTo("$base/catalog"))
                .andRespond(withSuccess("acme/small,acme/large", MediaType.TEXT_PLAIN))

            assertEquals(listOf("acme/small", "acme/large"), mocked.factory.modelNames())
            assertEquals("acme/small", mocked.factory.buildValidated().name)
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
        fun `interrupted validation cancels before resolving even a missing credential`() {
            for (key in listOf(null, "", "test-key")) {
                val calls = AtomicInteger()
                val mocked = mocked(key = { key.also { calls.incrementAndGet() } })
                Thread.currentThread().interrupt()
                try {
                    val failure = assertThrows<CancellationException> { mocked.factory.buildValidated() }
                    assertTrue(failure.cause is InterruptedException)
                } finally {
                    assertTrue(Thread.interrupted())
                }
                assertEquals(0, calls.get())
                mocked.server.verify()
            }
        }

        @Test
        fun `a blank key is invalid without calling the endpoint`() {
            val mocked = mocked(key = { "" })

            assertThrows<InvalidApiKeyException> { mocked.factory.buildValidated() }
            mocked.server.verify()
        }

        @Test
        fun `validation reads the key once and lists models with that key`() {
            val keys = ArrayDeque(listOf("checked-key", ""))
            val mocked = mocked(key = { keys.removeFirst() })
            mocked.server.expect(requestTo("$base/v1/models"))
                .andExpect { assertEquals("Bearer checked-key", it.headers.getFirst(HttpHeaders.AUTHORIZATION)) }
                .andRespond(withSuccess("""{"models":[{"name":"model-1"}]}""", MediaType.APPLICATION_JSON))

            mocked.factory.buildValidated()

            mocked.server.verify()
            assertEquals(1, keys.size)
        }

        @Test
        fun `validation interrupted in the key supplier is cancelled`() {
            val mocked = mocked(key = { throw IllegalStateException(InterruptedException()) })

            try {
                assertThrows<CancellationException> { mocked.factory.buildValidated() }
            } finally {
                assertTrue(Thread.interrupted())
            }
        }

        @Test
        fun `validation interrupted before the listing is cancelled without a request`() {
            val mocked = mocked()

            Thread.currentThread().interrupt()
            try {
                assertThrows<CancellationException> { mocked.factory.buildValidated() }
            } finally {
                assertTrue(Thread.interrupted())
            }
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
        private val release = CountDownLatch(1)

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
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out -> repeat(64) { out.write(ByteArray(1024)) } }
            }
            server.createContext("/slow/v1/systemone") { exchange ->
                exchange.requestBody.readAllBytes()
                release.await(5, TimeUnit.SECONDS)
                exchange.close()
            }
            server.start()
        }

        @AfterEach
        fun stop() {
            release.countDown()
            server.stop(0)
        }

        private fun factory(
            readTimeout: Duration = Duration.ofSeconds(5),
            port: Int = server.address.port,
            path: String = "",
        ) = SystemOneModelFactory(
            SystemOneEndpoint("Example", URI.create("http://127.0.0.1:$port$path"), readTimeout = readTimeout, maxResponseBytes = 1024),
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
        fun `the fallback transport applies the endpoint's read timeout`() {
            val slow = factory(readTimeout = Duration.ofMillis(200), path = "/slow")

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
