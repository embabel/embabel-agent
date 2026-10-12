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
package com.embabel.agent.clef

import com.embabel.common.ai.classification.*
import com.embabel.common.ai.decision.*
import io.micrometer.observation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class ClefDecisionServiceTest {
    private val mapper = JsonMapper.builder().build()
    private val choice = Questions.named("public \"team☃").choice("Choose the team")
        .option("bill☃", "Payments").option("support\\team", "Support").build()
    private val proposition = Questions.named("urgent").proposition("Is it urgent?").build()
    private val rating = Questions.named("effort").rating("Effort?").level("easy", "Easy").level("hard", "Hard").build()
    private val spec = DecisionSpec.of(proposition, choice, rating)

    private inner class Endpoint : AutoCloseable {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        var headers: List<String>? = listOf(header())
        var status = 200
        var body: ((JsonNode) -> String) = ::fixture
        var delay = 0L
        var bodyDelay = 0L
        var received: JsonNode? = null
        init {
            server.executor = executor
            server.createContext("/v1/systemone") { exchange ->
                calls.incrementAndGet()
                try {
                    val request = mapper.readTree(exchange.requestBody.readAllBytes()); received = request
                    if (delay > 0) Thread.sleep(delay)
                    headers?.forEach { exchange.responseHeaders.add("X-Embabel-Clef-Provenance", it) }
                    val bytes = body(request).toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    if (bodyDelay > 0) Thread.sleep(bodyDelay)
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: Exception) { } finally { exchange.close() }
            }
            server.start()
        }
        fun uri() = URI("http://127.0.0.1:${server.address.port}")
        fun factory(timeout: Duration = Duration.ofSeconds(2), maxResponseBytes: Int = 65536,
                    observations: ObservationRegistry = ObservationRegistry.NOOP) =
            ClefModelFactory(ClefClientOptions(uri(), timeout, maxResponseBytes = maxResponseBytes), observations)
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    private fun header(): String = mapper.writeValueAsString(linkedMapOf(
        "source_repo" to "FluidInference/clef-text-0.6b-coreml", "source_revision" to "3c61a60954478ef385aa42d67d5764642dcc9135",
        "variant" to "local-single-function-L1024", "license" to "Apache-2.0", "requested_compute" to "cpu",
        "asset_sha256" to ClefProtocol.PINNED_HASHES, "checker_sha256" to "a".repeat(64),
        "checker_source_revision" to "0230049c4e81f8de53ae1086b20cd9106c511105", "checker_build_id" to "test-build",
        "checker_provenance" to "operator-supplied source/build; digest identifies bytes, not origin",
        "confidence_semantics" to "selected_probability", "protocol" to "embabel-clef-systemone-v1", "experimental" to true,
    ))

    private fun fixture(request: JsonNode): String {
        val answers = linkedMapOf<String, Any>()
        for (name in request.get("questions").propertyNames()) {
            val question = request.get("questions").get(name)
            answers[name] = when (question.get("type").stringValue()) {
                "noul" -> mapOf("type" to "noul", "noul" to 0.8)
                "choice" -> {
                    val ids = question.get("criteria").propertyNames().toList()
                    val probabilities = ids.mapIndexed { i, id -> id to if (i == 0) 1.0 else 0.0 }.toMap()
                    mapOf("type" to "choice", "choice" to ids[0], "confidence" to 1.0, "probabilities" to probabilities)
                }
                else -> {
                    val levels = question.get("criteria").values().map { it.stringValue() }
                    val probabilities = levels.indices.associate { it.toString() to if (it == 0) 1.0 else 0.0 }
                    mapOf("type" to "score", "score" to 0.0, "confidence" to 1.0,
                        "probabilities" to probabilities, "legend" to levels.mapIndexed { i, value -> i.toString() to value }.toMap())
                }
            }
        }
        return mapper.writeValueAsString(mapOf("model" to "clef-text-0.6b-coreml", "answers" to answers,
            "usage" to mapOf("input_tokens" to 80, "output_tokens" to 0)))
    }

    @Nested
    inner class Construction {
        @Test
        fun `local catalog construction describe and custom options have zero endpoint calls`() {
            Endpoint().use { endpoint -> endpoint.factory().use { factory ->
                assertEquals("clef", factory.id)
                assertEquals(listOf("clef-text-0.6b-coreml"), factory.models().map { it.modelName })
                assertEquals(factory.describe(ClefModelFactory.MODEL).capabilities, factory.create(ClefModelFactory.MODEL).capabilities())
                assertThrows<IllegalArgumentException> { factory.create("unknown") }
                assertEquals(0, endpoint.calls.get())
                assertFalse(factory.toString().contains(endpoint.uri().toString()))
            } }
        }
        @Test
        fun `only explicit loopback HTTP and bounded transport settings accepted`() {
            listOf("https://localhost", "http://example.com", "http://127.0.0.1?private", "http://user:private@localhost", "file:///private").forEach {
                val error = assertThrows<IllegalArgumentException> { ClefClientOptions(URI(it)) }
                assertFalse(error.toString().contains("private"))
            }
            assertThrows<IllegalArgumentException> { ClefClientOptions(URI("http://localhost"), Duration.ZERO) }
            assertThrows<IllegalArgumentException> { ClefClientOptions(URI("http://localhost"), maxRequestBytes = 16385) }
            assertThrows<IllegalArgumentException> { ClefClientOptions(URI("http://localhost"), maxResponseBytes = 65537) }
        }
    }

    @Nested
    inner class Evidence {
        @Test
        fun `one joint typed ask preserves public ids ordered full evidence and observation`() {
            val operations = mutableListOf<String>()
            val registry = ObservationRegistry.create()
            registry.observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStop(context: Observation.Context) { operations += context.name }
            })
            Endpoint().use { endpoint -> endpoint.factory(observations = registry).use { factory ->
                val response = factory.create(ClefModelFactory.MODEL).ask("Support request", spec)
                assertNull(response.requestFailure)
                assertEquals(1, endpoint.calls.get())
                assertEquals(listOf("urgent", choice.name, "effort"), response.answers.map { it.name })
                val yes = response.answer(proposition) as PropositionResult.Answered
                assertEquals(0.8, yes.pTrue); assertEquals("CLEF", yes.provenance.provider)
                assertTrue(yes.provenance.version!!.contains("local-single-function-L1024"))
                assertTrue(yes.provenance.version!!.contains("a".repeat(64)))
                assertEquals(listOf("bill☃", "support\\team"), response.choiceDistribution(choice)!!.probabilities.map { it.categoryId })
                val score = response.answer(rating) as RatingResult.Answered
                assertNull(score.selectedLevelId); assertEquals(0.0, score.score!!.value)
                assertEquals(listOf("easy", "hard"), score.distribution.map { it.levelId })
                assertTrue(operations.contains("embabel.ai.ask")); assertTrue(operations.contains("embabel.ai.decision"))
                assertEquals(listOf("q001", "q002", "q003"), endpoint.received!!.get("questions").propertyNames().toList())
            } }
        }
        @Test
        fun `exact half loses pTrue honestly and zero confidence stays selected shared behavior`() {
            Endpoint().use { endpoint -> endpoint.body = { fixture(it).replace("\"noul\":0.8", "\"noul\":0.5").replace("\"confidence\":1.0", "\"confidence\":0.0") }
                endpoint.factory().use { factory ->
                    val response = factory.create(ClefModelFactory.MODEL).ask("input", spec)
                    assertTrue(response.answer(proposition) is PropositionResult.Inconclusive)
                    assertEquals(0.0, (response.answer(choice) as ClassificationResult.Selected).confidence)
                }
            }
        }
        @Test
        fun `more than nine options and hostile labels retain order and descriptions`() {
            val question = Questions.named("hostile").choice("Choose").apply {
                (1..12).forEach { option("id-$it☃\\", "Description $it") }
            }.build()
            Endpoint().use { endpoint -> endpoint.factory().use { factory ->
                val response = factory.create(ClefModelFactory.MODEL).ask("input", DecisionSpec.of(question))
                assertEquals(question.options.map { it.id }, response.choiceDistribution(question)!!.probabilities.map { it.categoryId })
                assertEquals(question.options.map { it.description }, endpoint.received!!.get("questions").get("q001").get("criteria").values().map { it.stringValue() })
            } }
        }
    }

    @Nested
    inner class Rejections {
        @Test
        fun `missing duplicate malformed stale and tampered provenance are invalid private failures`() {
            Endpoint().use { endpoint -> endpoint.factory().use { factory ->
                val mutations: List<List<String>?> = listOf(null, emptyList(), listOf(header(), header()), listOf("{}"),
                    listOf(header()+" {}"), listOf(header().replace("\"cpu\"", "\"gpu\"")),
                    listOf(header().replace("3c61a60954478ef385aa42d67d5764642dcc9135", "b".repeat(40))),
                    listOf(header().replace("\"experimental\":true", "\"experimental\":1")),
                    listOf(header().replace("\"protocol\":", "\"protocol\":\"duplicate\",\"protocol\":")),
                    listOf(header().replace("a".repeat(64), "BAD_CHECKER")),
                    listOf(header().replace("test-build", "private/path")), listOf("x".repeat(8193)))
                mutations.forEach { value -> endpoint.headers = value
                    val response = factory.create(ClefModelFactory.MODEL).ask("PRIVATE_INPUT", spec)
                    assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
                    assertFalse(response.toString().contains("PRIVATE_INPUT"))
                }
                ClefProtocol.PINNED_HASHES.values.forEach { hash -> endpoint.headers = listOf(header().replace(hash, "b".repeat(64)))
                    assertEquals(FailureReason.INVALID_RESPONSE, factory.create(ClefModelFactory.MODEL).ask("input", spec).requestFailure)
                }
                endpoint.headers = listOf(header()); endpoint.body = { fixture(it).replace("clef-text-0.6b-coreml", "stale-model") }
                assertEquals(FailureReason.INVALID_RESPONSE, factory.create(ClefModelFactory.MODEL).ask("input", spec).requestFailure)
            } }
        }
        @Test
        fun `native limits reject before HTTP including UTF8 fallback and aggregate head`() {
            Endpoint().use { endpoint -> endpoint.factory().use { factory ->
                val requests = listOf(
                    DecisionRequest.of("☃".repeat(1366), spec),
                    DecisionRequest.of("input", DecisionSpec.of((1..17).map { Questions.named("q$it").proposition("Question").build() })),
                    DecisionRequest.of("input", Questions.named("q").proposition("x".repeat(513)).build()),
                    DecisionRequest.of("input", Questions.named("q").choice("Choose").option("only", "Only").build()),
                    DecisionRequest.of("input", Questions.named("q").choice("Choose").apply { (1..33).forEach { option("o$it", "Option") } }.build()),
                    DecisionRequest.of("input", Questions.named("q").choice("Choose").option("x".repeat(513), "").option("b", "B").build()),
                    DecisionRequest.of("input", Questions.named("q").rating("Rate").apply { (1..17).forEach { level("l$it", "Level") } }.build()),
                    DecisionRequest.of("input", DecisionSpec.of((1..4).map { n -> Questions.named("q$n").choice("Choose").apply { (1..32).forEach { option("o$it", "Option") } }.build() })),
                )
                requests.forEach { assertEquals(FailureReason.INVALID_RESPONSE, factory.create(ClefModelFactory.MODEL).ask(it).requestFailure) }
                assertEquals(0, endpoint.calls.get())
            } }
        }
        @Test
        fun `profile status mapping shares transport recovery and full body deadline`() {
            Endpoint().use { endpoint -> endpoint.factory(timeout = Duration.ofMillis(150), maxResponseBytes = 2048).use { factory ->
                val service = factory.create(ClefModelFactory.MODEL)
                endpoint.status = 502
                assertEquals(FailureReason.INVALID_RESPONSE, service.ask("input", spec).requestFailure)
                endpoint.status = 503
                assertEquals(FailureReason.UNAVAILABLE, service.ask("input", spec).requestFailure)
                endpoint.status = 200; endpoint.body = { "PRIVATE".repeat(1000) }
                assertEquals(FailureReason.INVALID_RESPONSE, service.ask("input", spec).requestFailure)
                endpoint.body = ::fixture; endpoint.bodyDelay = 500
                assertEquals(FailureReason.UNAVAILABLE, service.ask("input", spec).requestFailure)
                endpoint.bodyDelay = 0
                assertNull(service.ask("input", spec).requestFailure)
            } }
        }
        @Test
        fun `shared client concurrent requests recover and interrupted call preserves cancellation`() {
            Endpoint().use { endpoint -> endpoint.factory().use { factory ->
                val service = factory.create(ClefModelFactory.MODEL)
                val pool = Executors.newFixedThreadPool(4)
                try {
                    val futures = (1..4).map { pool.submit<DecisionResponse> { service.ask("input", spec) } }
                    futures.forEach { assertNull(it.get(5, TimeUnit.SECONDS).requestFailure) }
                    assertEquals(4, endpoint.calls.get())
                } finally { pool.shutdownNow() }
                endpoint.delay = 1000
                val cancelled = java.util.concurrent.atomic.AtomicBoolean()
                val worker = Thread {
                    try { service.ask("input", spec) } catch (_: CancellationException) {
                        cancelled.set(Thread.currentThread().isInterrupted)
                    }
                }
                worker.start()
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (endpoint.calls.get() < 5 && System.nanoTime() < end) Thread.sleep(10)
                worker.interrupt(); worker.join(2000)
                assertFalse(worker.isAlive); assertTrue(cancelled.get())
                endpoint.delay = 0
                assertNull(service.ask("input", spec).requestFailure)
            } }
        }

    }
}
