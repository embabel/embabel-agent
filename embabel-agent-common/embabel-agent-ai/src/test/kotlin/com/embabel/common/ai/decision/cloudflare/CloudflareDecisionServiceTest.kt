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
package com.embabel.common.ai.decision.cloudflare

import com.embabel.common.ai.classification.*
import com.embabel.common.ai.decision.*
import com.embabel.common.ai.decision.systemone.SystemOneCodec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.net.*
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class CloudflareDecisionServiceTest {
    private val proposition = Questions.named("受取-\"\\").proposition("Was the parcel delivered?").build()
    private val choice = Questions.named("route").choice("Choose parcel state")
        .option("delivered", "Delivered").option("returned", "Returned").build()
    private val rating = Questions.named("condition").rating("Rate parcel condition")
        .level("low", "Low").level("high", "High").build()
    private val spec = DecisionSpec.of(proposition, choice, rating)

    @Nested
    inner class Evidence {
        @Test
        fun `schema derived REST mock preserves false probability zero confidence order and public model provenance`() {
            CloudflarePeer().use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("A synthetic parcel was returned unopened.", spec)
                assertNull(response.requestFailure)
                assertEquals(spec.questions.map { it.name }, response.answers.map { it.name })
                val assessed = response.answer(proposition) as PropositionResult.Answered
                assertFalse(assessed.answer)
                assertEquals(0.2, assessed.pTrue)
                assertEquals(CLEF, assessed.provenance.modelName)
                assertEquals("Cloudflare Workers AI", assessed.provenance.provider)
                assertNull(assessed.provenance.version)
                assertNull(assessed.provenance.requestId)
                assertEquals(0.0, (response.answer(choice) as ClassificationResult.Selected).confidence)
                assertEquals(listOf(CategoryProbability("delivered", 0.25), CategoryProbability("returned", 0.75)),
                    response.choiceDistribution(choice)!!.probabilities)
                val scored = response.answer(rating) as RatingResult.Answered
                assertEquals(RatingScore(0.75, RatingStatistic.EXPECTED_LEVEL_INDEX), scored.score)
                assertNull(scored.selectedLevelId)
                assertEquals(listOf(LevelProbability("low", 0.25), LevelProbability("high", 0.75)), scored.distribution)
                assertEquals(0.0, scored.confidence)
                assertEquals(1, peer.received.get())
            } }
        }

        @Test
        fun `actual derived URI body selector and Bearer are correct for both known models`() {
            listOf(CLEF, FLASH).forEach { model ->
                CloudflarePeer(CloudflarePeer.fixture().replace("\"clef\"", "\"${model.substringAfterLast('/')}\""))
                    .use { peer -> peer.factory().use { factory ->
                        assertNull(factory.create(model).ask("Unicode state 文", spec).requestFailure)
                        val target = peer.targets.single()
                        assertEquals("https", target.scheme)
                        assertEquals("api.cloudflare.com", target.host)
                        assertEquals("/client/v4/accounts/$ACCOUNT/ai/run/$model", target.path)
                        assertNull(target.rawQuery)
                        assertNull(target.rawUserInfo)
                        assertEquals(target.path, peer.paths.single())
                        assertEquals("POST", peer.methods.single())
                        assertEquals(listOf("Bearer synthetic-token"), peer.authorizations.toList())
                        val sent = SystemOneCodec.read(peer.requests.single())
                        assertEquals(model.substringAfterLast('/'), sent.get("model").stringValue())
                        assertEquals("Unicode state 文", sent.get("state").stringValue())
                        assertEquals(listOf("q001", "q002", "q003"), sent.get("questions").propertyNames().toList())
                        assertFalse(String(peer.requests.single(), UTF_8).contains(proposition.name))
                        assertEquals(1, peer.received.get())
                    } }
            }
        }

        @Test
        fun `full model identity accepted but no runtime or arbitrary header claims are invented`() {
            CloudflarePeer(CloudflarePeer.fixture().replace("\"clef\"", "\"$CLEF\"" )).use { peer ->
                peer.handler = { exchange ->
                    exchange.responseHeaders.add("X-Typesafe-Request-Id", "private-header")
                    exchange.responseHeaders.add("X-Engine-Version", "private-version")
                    peer.respond(exchange, peer.body)
                }
                peer.factory().use { factory ->
                    val answer = factory.create(CLEF).ask("state", spec).answer(proposition) as PropositionResult.Answered
                    assertEquals(CLEF, answer.provenance.modelName)
                    assertNull(answer.provenance.version)
                    assertNull(answer.provenance.requestId)
                }
            }
        }

        @Test
        fun `twelve Unicode questions and 255 options preserve public binding and raw distribution within selected caps`() {
            val options = (0 until 255).map { "公-$it" }
            val c = Questions.named("公-choice").choice("Choose")
            options.forEach { c.option(it, "Synthetic option $it") }
            val questions = (1..11).map { Questions.named("私-$it").proposition("True $it?").build() } + c.build()
            val answers: MutableMap<String, Any> = (1..11).associate { "q%03d".format(it) to mapOf("type" to "noul", "noul" to 0.2) }.toMutableMap()
            answers["q012"] = mapOf("type" to "choice", "choice" to options.last(), "confidence" to 0.0,
                "probabilities" to options.associateWith { if (it == options.last()) 1.0 else 0.0 })
            CloudflarePeer(CloudflarePeer.envelope(answers)).use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("Synthetic state", DecisionSpec.of(questions))
                assertEquals(questions.map { it.name }, response.answers.map { it.name })
                val distribution = response.choiceDistribution(questions.last() as ChoiceQuestionSpec)!!
                assertEquals(options, distribution.probabilities.map { it.categoryId })
                assertEquals(1.0, distribution.probabilities.last().probability)
                val request = SystemOneCodec.read(peer.requests.single())
                assertEquals((1..12).map { "q%03d".format(it) }, request.get("questions").propertyNames().toList())
                assertTrue(peer.requests.single().size < 262144)
                assertTrue(peer.body.toByteArray(UTF_8).size < 1048576)
            } }
        }
    }

    @Nested
    inner class PublishedLimits {
        @Test
        fun `large complete domains exceed generic transport defaults while fitting Cloudflare selected caps`() {
            val ids = (0 until 255).map { "option-$it" }
            val questions = (1..24).map { q ->
                val choice = Questions.named("choice-$q").choice("Choose synthetic option $q")
                ids.forEach { choice.option(it, "Option $it") }
                choice.build()
            }
            val answer = mapOf("type" to "choice", "choice" to ids.last(), "confidence" to 0.0,
                "probabilities" to ids.associateWith { if (it == ids.last()) 1.0 else 0.0 })
            val answers = (1..24).associate { "q%03d".format(it) to answer }
            val body = CloudflarePeer.envelope(answers)
            assertTrue(body.toByteArray(UTF_8).size > 65536)
            CloudflarePeer(body).use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("Synthetic large domain state", DecisionSpec.of(questions))
                assertNull(response.requestFailure)
                assertTrue(peer.requests.single().size in 16385..262144)
                assertTrue(body.toByteArray(UTF_8).size <= 1048576)
                questions.forEach { question ->
                    assertEquals(255, response.choiceDistribution(question)!!.probabilities.size)
                    assertEquals(ids.last(), (response.answer(question) as ClassificationResult.Selected).categoryId)
                }
                assertEquals(1, peer.received.get())
            } }
        }

        @Test
        fun `64 questions and ten rating levels are accepted with exact zero based expected score`() {
            val ratingBuilder = Questions.named("rating-max").rating("Rate synthetic state")
            (0..9).forEach { ratingBuilder.level("level-$it", "Level $it") }
            val maxRating = ratingBuilder.build()
            val questions = (1..63).map { Questions.named("p-$it").proposition("True?").build() } + maxRating
            val answers: MutableMap<String, Any> = (1..63).associate {
                "q%03d".format(it) to mapOf("type" to "noul", "noul" to 0.2)
            }.toMutableMap()
            answers["q064"] = mapOf("type" to "score", "score" to 9.0, "confidence" to 0.0,
                "legend" to (0..9).associate { it.toString() to "Level $it" },
                "probabilities" to (0..9).associate { it.toString() to if (it == 9) 1.0 else 0.0 })
            CloudflarePeer(CloudflarePeer.envelope(answers)).use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("Synthetic state", DecisionSpec.of(questions))
                assertNull(response.requestFailure)
                assertEquals(64, response.answers.size)
                assertEquals(RatingScore(9.0, RatingStatistic.EXPECTED_LEVEL_INDEX),
                    (response.answer(maxRating) as RatingResult.Answered).score)
                assertEquals(64, SystemOneCodec.read(peer.requests.single()).get("questions").size())
            } }
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `partial and malformed answers preserve valid siblings and strict rounded values are rejected`() {
            val base = CloudflarePeer.fixture()
            listOf(
                base.replace("\"returned\":0.75", "\"returned\":0.749998"),
                base.replace("\"returned\":0.75", "\"other\":0.75"),
                base.replace("\"confidence\":0.0", "\"confidence\":1.1"),
                base.replace("\"type\":\"choice\"", "\"type\":\"noul\""),
                base.replace("\"choice\":\"returned\"", "\"choice\":\"delivered\""),
            ).forEach { body -> CloudflarePeer(body).use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("state", spec)
                assertNull(response.requestFailure)
                assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(choice))
                assertNull(response.choiceDistribution(choice))
                assertTrue(response.answer(proposition) is PropositionResult.Answered)
            } } }
            val root = JsonMapper.builder().build().readTree(base)
            (root.get("result").get("answers") as tools.jackson.databind.node.ObjectNode).remove("q002")
            CloudflarePeer(root.toString()).use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("state", spec)
                assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(choice))
                assertTrue(response.answer(rating) is RatingResult.Answered)
            } }
        }

        @Test
        fun `score domain legend expected value and required answer type are strict`() {
            val base = CloudflarePeer.fixture()
            listOf(
                base.replace("\"score\":0.75", "\"score\":0.750002"),
                base.replace("\"0\":\"Low\"", "\"0\":\"Other\""),
                base.replace("\"1\":0.75", "\"01\":0.75"),
                base.replace("\"type\":\"score\",", ""),
                base.replace("\"1\":0.75", "\"1\":1e999"),
            ).forEach { body -> CloudflarePeer(body).use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("state", spec)
                assertEquals(RatingResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(rating))
                assertTrue(response.answer(choice) is ClassificationResult.Selected)
            } } }
        }

        @Test
        fun `REST success errors root model and usage reject malformed or nested envelopes with sanitized failures`() {
            val base = CloudflarePeer.fixture()
            val inner = SystemOneCodec.read(base.toByteArray(UTF_8)).get("result").toString()
            listOf("{}", "[]", "not json", base + "{}", inner,
                base.replace("\"success\":true", "\"success\":false"),
                base.replace("\"success\":true", "\"success\":\"true\""),
                base.replace("\"errors\":[]", "\"errors\":[{\"message\":\"PRIVATE\"}]"),
                base.replace("\"errors\":[]", "\"errors\":{}"),
                base.replace("\"clef\"", "\"clef-flash\""),
                base.replace("\"input_tokens\":18", "\"input_tokens\":-1"),
                base.replace("\"output_tokens\":0", "\"output_tokens\":0.5"),
                """{"success":true,"errors":[],"messages":[],"result":$base}""",
                SystemOneCodec.read(CloudflarePeer.resource()).get("error").toString(),
            ).forEach { body -> CloudflarePeer(body).use { peer -> peer.factory().use { factory ->
                val response = factory.create(CLEF).ask("PRIVATE state", spec)
                assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
                assertFalse(response.toString().contains("PRIVATE"))
            } } }
        }

        @Test
        fun `missing blank throwing and hostile tokens never make HTTP and supplier resolves once per call`() {
            listOf<String?>(null, "", "  ").forEach { token -> CloudflarePeer().use { peer ->
                var credentials = 0
                peer.factory(token = Supplier { credentials++; token }).use { factory ->
                    val response = factory.create(CLEF).ask("state", spec)
                    assertEquals(FailureReason.UNAVAILABLE, response.requestFailure)
                    assertEquals(1, credentials)
                    assertEquals(0, peer.received.get())
                }
            } }
            listOf(Supplier<String?> { error("PRIVATE") }, Supplier<String?> { "PRIVATE\ninvalid" }).forEach { token ->
                CloudflarePeer().use { peer -> peer.factory(token = token).use { factory ->
                    assertEquals(FailureReason.INVALID_RESPONSE, factory.create(CLEF).ask("state", spec).requestFailure)
                    assertEquals(0, peer.received.get())
                } }
            }
            CloudflarePeer().use { peer ->
                var credentials = 0
                peer.factory(token = Supplier { "synthetic-${++credentials}" }).use { factory ->
                    repeat(2) { factory.create(CLEF).ask("state", spec) }
                    assertEquals(2, credentials)
                    assertEquals(listOf("Bearer synthetic-1", "Bearer synthetic-2"), peer.authorizations.toList())
                    assertEquals(2, peer.received.get())
                }
            }
        }

        @Test
        fun `unsupported input and UTF8 encoded oversize reject before credentials or HTTP`() {
            val excessiveChoices = Questions.named("choice").choice("Choose")
            (1..256).forEach { excessiveChoices.option("o$it", "Option $it") }
            val excessiveRating = Questions.named("rating").rating("Rate")
            (1..11).forEach { excessiveRating.level("l$it", "Level $it") }
            val excessiveQuestions = (1..65).map { Questions.named("p$it").proposition("True?").build() }
            CloudflarePeer().use { peer ->
                var credentials = 0
                peer.factory(token = Supplier { credentials++; "token" }).use { factory ->
                    listOf(DecisionSpec.of(excessiveChoices.build()), DecisionSpec.of(excessiveRating.build()),
                        DecisionSpec.of(excessiveQuestions)).forEach { unsupported ->
                        assertThrows<UnsupportedDecisionException> { factory.create(CLEF).ask("state", unsupported) }
                    }
                    assertThrows<UnsupportedDecisionException> { factory.create(CLEF).ask("文".repeat(100000), spec) }
                    assertEquals(0, credentials)
                    assertEquals(0, peer.received.get())
                }
            }
        }
    }

    @Nested
    inner class Transport {
        @Test
        fun `rate limits invalid requests and redirects are typed with no retry or auth forwarding`() {
            CloudflarePeer().use { destination ->
                listOf(400 to FailureReason.INVALID_RESPONSE, 422 to FailureReason.INVALID_RESPONSE,
                    429 to FailureReason.UNAVAILABLE, 503 to FailureReason.UNAVAILABLE, 302 to FailureReason.UNAVAILABLE)
                    .forEach { (status, reason) -> CloudflarePeer().use { peer ->
                        peer.handler = { exchange ->
                            exchange.responseHeaders.add("Location", destination.uri.toString())
                            peer.respond(exchange, "PRIVATE payload", status)
                        }
                        peer.factory().use { factory ->
                            assertEquals(reason, factory.create(CLEF).ask("state", spec).requestFailure)
                            assertEquals(1, peer.received.get())
                        }
                    } }
                assertEquals(0, destination.received.get())
            }
        }

        @Test
        fun `oversized response and retained headers fail through the production client`() {
            CloudflarePeer().use { peer -> peer.factory(options = CloudflareClientOptions(ACCOUNT, maxResponseBytes = 64)).use { factory ->
                assertEquals(FailureReason.INVALID_RESPONSE, factory.create(CLEF).ask("state", spec).requestFailure)
                assertEquals(1, peer.received.get())
            } }
            CloudflarePeer().use { peer ->
                peer.handler = { exchange ->
                    exchange.responseHeaders.add("X-Private", "x".repeat(8193))
                    peer.respond(exchange, peer.body)
                }
                peer.factory().use { factory ->
                    assertEquals(FailureReason.INVALID_RESPONSE, factory.create(CLEF).ask("state", spec).requestFailure)
                }
            }
        }

        @Test
        fun `stalled streamed body respects total deadline cleans connection and allows next call`() {
            CloudflarePeer().use { peer ->
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                peer.handler = { exchange ->
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write('{'.code)
                    exchange.responseBody.flush()
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                peer.factory(options = CloudflareClientOptions(ACCOUNT, Duration.ofMillis(250))).use { factory ->
                    val before = System.nanoTime()
                    try {
                        assertEquals(FailureReason.UNAVAILABLE, factory.create(CLEF).ask("state", spec).requestFailure)
                        assertTrue(started.await(1, TimeUnit.SECONDS))
                        assertTrue(Duration.ofNanos(System.nanoTime() - before) < Duration.ofSeconds(2))
                    } finally { release.countDown() }
                    peer.handler = { exchange -> peer.respond(exchange, peer.body) }
                    assertNull(factory.create(CLEF).ask("state", spec).requestFailure)
                    assertEquals(2, peer.received.get())
                }
            }
        }

        @Test
        fun `EOF after counted POST is unavailable without retry and next exchange works`() {
            CloudflareEofPeer().use { peer -> peer.factory().use { factory ->
                assertEquals(FailureReason.UNAVAILABLE, factory.create(CLEF).ask("state", spec).requestFailure)
                assertNull(factory.create(CLEF).ask("state", spec).requestFailure)
                assertEquals(2, peer.received.get())
            } }
        }

        @Test
        fun `close prevents further transport and ask hooks retain observation`() {
            val registry = ObservationRegistry.create()
            val stopped = mutableListOf<String>()
            registry.observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStop(context: Observation.Context) { stopped += requireNotNull(context.name) }
            })
            CloudflarePeer().use { peer ->
                val factory = peer.factory(registry = registry)
                val service = factory.create(CLEF)
                assertNull(service.ask("state", spec).requestFailure)
                assertTrue(stopped.contains("embabel.ai.ask"))
                factory.close()
                assertEquals(FailureReason.UNAVAILABLE, service.ask("state", spec).requestFailure)
                assertEquals(1, peer.received.get())
            }
        }
    }
}

internal class CloudflarePeer(val body: String = fixture()) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val uri = URI("http://127.0.0.1:${server.address.port}")
    val received = AtomicInteger()
    val targets = CopyOnWriteArrayList<URI>()
    val paths = CopyOnWriteArrayList<String>()
    val methods = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<ByteArray>()
    val authorizations = CopyOnWriteArrayList<String>()
    @Volatile var handler: (HttpExchange) -> Unit = { respond(it, body) }
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                requests += exchange.requestBody.readAllBytes()
                paths += exchange.requestURI.path
                methods += exchange.requestMethod
                authorizations += exchange.requestHeaders.getFirst("Authorization") ?: ""
                received.incrementAndGet()
                handler(exchange)
            }
        }
        server.start()
    }
    fun factory(options: CloudflareClientOptions = CloudflareClientOptions(ACCOUNT), names: List<String> = listOf(CLEF, FLASH),
                token: Supplier<String?> = Supplier { "synthetic-token" }, registry: ObservationRegistry = ObservationRegistry.NOOP) =
        CloudflareModelFactory(options, names, registry, token) { client, target, payload ->
            targets += target
            client.exchange(uri.resolve(target.rawPath), payload)
        }
    fun respond(exchange: HttpExchange, value: String, status: Int = 200) {
        val bytes = value.toByteArray(UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
    }
    companion object {
        fun resource() = CloudflarePeer::class.java.getResourceAsStream("/decision/cloudflare/clef-text-contract.json")!!.use { it.readAllBytes() }
        fun fixture(): String = SystemOneCodec.read(resource()).get("success").toString()
        fun envelope(answers: Map<String, Any>): String = JsonMapper.builder().build().writeValueAsString(mapOf(
            "success" to true, "errors" to emptyList<String>(), "messages" to emptyList<String>(),
            "result" to mapOf("model" to "clef", "answers" to answers, "usage" to mapOf("input_tokens" to 0, "output_tokens" to 0))))
    }
}

private class CloudflareEofPeer : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val received = AtomicInteger()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val peer = Thread({
        try {
            while (!socket.isClosed) socket.accept().use { connection ->
                connection.soTimeout = 2000
                val input = connection.getInputStream()
                fun line(): String {
                    val bytes = mutableListOf<Byte>()
                    while (true) {
                        val b = input.read()
                        check(b >= 0)
                        if (b == 10) break
                        if (b != 13) bytes += b.toByte()
                    }
                    return bytes.toByteArray().toString(UTF_8)
                }
                check(line().startsWith("POST /client/v4/accounts/$ACCOUNT/ai/run/$CLEF "))
                var length = 0
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                check(length in 1..262144)
                check(input.readNBytes(length).size == length)
                if (received.incrementAndGet() != 1) {
                    val body = CloudflarePeer.fixture().toByteArray(UTF_8)
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(UTF_8))
                    connection.getOutputStream().write(body)
                }
            }
        } catch (e: SocketException) { if (!socket.isClosed) errors += e }
        catch (e: Throwable) { errors += e }
    }, "cloudflare-eof-test-peer").apply { isDaemon = true; start() }
    fun factory() = CloudflareModelFactory(CloudflareClientOptions(ACCOUNT, Duration.ofSeconds(1)), listOf(CLEF),
        ObservationRegistry.NOOP, Supplier { "synthetic-token" }) { client, target, payload ->
        client.exchange(URI("http://127.0.0.1:${socket.localPort}").resolve(target.rawPath), payload)
    }
    override fun close() {
        socket.close()
        peer.join(2500)
        assertFalse(peer.isAlive)
        assertTrue(errors.isEmpty(), "Test peer failure: $errors")
    }
}
