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
package com.embabel.agent.decision.example

import com.embabel.agent.decision.*
import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.tools.ToolProvider

class JevDecisionExampleTest {
    @Test
    fun `both language consumers return typed values distributions and provenance through TypeSafe`() {
        ScriptedExampleServer.replying(exampleResponse()).use { server ->
            val kotlin = runKotlinDecision(model(server))
            val java = JevDecisionExample.run(model(server))
            assertThat(kotlin.eligible).isTrue()
            assertThat(kotlin.route).isEqualTo(KotlinRoute.REVIEW)
            assertThat(kotlin.urgency).isEqualTo(KotlinUrgency.HIGH)
            assertThat(kotlin.routeDistribution).containsExactlyInAnyOrderEntriesOf(
                mapOf(KotlinRoute.ACCEPT to 0.2, KotlinRoute.REVIEW to 0.6, KotlinRoute.REJECT to 0.2),
            )
            assertProvenance(kotlin.provenance)
            assertThat(java.eligible()).isTrue()
            assertThat(java.route()).isEqualTo(JevDecisionExample.Route.REVIEW)
            assertThat(java.urgency()).isEqualTo(JevDecisionExample.Urgency.HIGH)
            assertThat(java.routeDistribution()).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    JevDecisionExample.Route.ACCEPT to 0.2,
                    JevDecisionExample.Route.REVIEW to 0.6,
                    JevDecisionExample.Route.REJECT to 0.2,
                ),
            )
            assertProvenance(java.provenance())
            assertThat(server.requestCount).isEqualTo(2)
            server.requests.forEach {
                assertThat(it.method).isEqualTo("POST")
                assertThat(it.path).isEqualTo("/v1/systemone")
                assertThat(it.authorization).isEqualTo("Bearer synthetic-bearer")
                assertThat(it.body).contains("\"type\":\"noul\"", "\"type\":\"choice\"", "\"type\":\"score\"")
                assertThat(it.body).doesNotContain("synthetic-bearer")
            }
        }
    }

    @Test
    fun `ties preserve declared order and expose every maximizer`() {
        val body = envelope(
            "\"eligible\":{\"type\":\"noul\",\"noul\":0.5}," +
                "\"route\":{\"type\":\"choice\",\"choice\":\"accept\",\"confidence\":0.5,\"probabilities\":{\"accept\":0.5,\"review\":0.5,\"reject\":0.0}}," +
                "\"urgency\":{\"type\":\"score\",\"score\":1.0,\"confidence\":0.5,\"legend\":{\"0\":\"low\",\"1\":\"medium\",\"2\":\"high\"},\"probabilities\":{\"0\":0.5,\"1\":0.0,\"2\":0.5}}",
        )
        ScriptedExampleServer.replying(body).use { server ->
            val built = request()
            val success = model(server).ask(built.request) as DecisionOutcome.Success
            val yes = success.answer(built.eligible) as KeyOutcome.Success
            val route = success.answer(built.route) as KeyOutcome.Success
            val urgency = success.answer(built.urgency) as KeyOutcome.Success
            assertThat(yes.maximizers).containsExactly(false, true)
            assertThat(yes.firstMaximizer).isFalse()
            assertThat(route.maximizers).containsExactly(KotlinRoute.ACCEPT, KotlinRoute.REVIEW)
            assertThat(route.firstMaximizer).isEqualTo(KotlinRoute.ACCEPT)
            assertThat(urgency.maximizers).containsExactly(KotlinUrgency.LOW, KotlinUrgency.HIGH)
            assertThat(urgency.firstMaximizer).isEqualTo(KotlinUrgency.LOW)
            assertThat(urgency.expectedScore).isEqualTo(1.0)
        }
    }

    @Test
    fun `missing invalid and unsupported keys preserve a valid sibling`() {
        val body = envelope(
            "\"eligible\":{\"type\":\"noul\",\"noul\":0.8}," +
                "\"route\":{\"type\":\"choice\",\"choice\":\"review\",\"confidence\":0.3,\"probabilities\":{\"accept\":0.7,\"review\":0.3,\"reject\":0.0}}," +
                "\"urgency\":{\"type\":\"future-score\",\"value\":2}",
        )
        ScriptedExampleServer.replying(body).use { server ->
            val built = request(includeMissing = true)
            val success = model(server).ask(built.request) as DecisionOutcome.Success
            assertThat((success.answer(built.eligible) as KeyOutcome.Success).value).isTrue()
            assertThat((success.answer(built.route) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Invalid)
            assertThat((success.answer(built.urgency) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Unsupported)
            assertThat((success.answer(requireNotNull(built.missing)) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
        }
    }

    @Test
    fun `duplicate and unknown answer ids reject the whole call`() {
        val duplicate = """{"model":"resolved-example-v1","answers":{"eligible":{"type":"noul","noul":0.8},"eligible":{"type":"noul","noul":0.9}},"usage":{"input_tokens":7,"output_tokens":3}}"""
        val unknown = envelope(
            "\"eligible\":{\"type\":\"noul\",\"noul\":0.8},\"unknown\":{\"type\":\"noul\",\"noul\":1.0}",
        )
        listOf(duplicate, unknown).forEach { response ->
            ScriptedExampleServer.replying(response).use { server ->
                val failure = model(server).ask(request().request) as DecisionOutcome.Failure
                assertThat(failure.failure).isEqualTo(CallFailure.RejectedRequest)
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `malformed JSON and non success statuses fail safely`() {
        listOf(
            ScriptedExampleServer.Reply(200, "{"),
            ScriptedExampleServer.Reply(422, "{}"),
            ScriptedExampleServer.Reply(401, "SECRET_RESPONSE_MUST_NOT_ESCAPE"),
            ScriptedExampleServer.Reply(503, "{}"),
        ).forEach { reply ->
            ScriptedExampleServer.sequence(reply).use { server ->
                val failure = model(server).ask(request().request) as DecisionOutcome.Failure
                val expected = if (reply.status == 200 || reply.status == 422) CallFailure.RejectedRequest else CallFailure.Unavailable
                assertThat(failure.failure).isEqualTo(expected)
                assertThat(failure.safeCode.name).doesNotContain("SECRET")
                assertThat(failure.record?.fields?.values.orEmpty().joinToString()).doesNotContain("SECRET_RESPONSE_MUST_NOT_ESCAPE")
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `retry policy is exactly one retry for 429 and 529 and none for other failures`() {
        listOf(429, 529).forEach { retryable ->
            ScriptedExampleServer.sequence(
                ScriptedExampleServer.Reply(retryable, "{}"),
                ScriptedExampleServer.Reply(200, exampleResponse()),
            ).use { server ->
                assertThat(runKotlinDecision(model(server)).route).isEqualTo(KotlinRoute.REVIEW)
                assertThat(server.requestCount).isEqualTo(2)
            }
        }
        ScriptedExampleServer.sequence(
            ScriptedExampleServer.Reply(429, "{}"),
            ScriptedExampleServer.Reply(429, "{}"),
            ScriptedExampleServer.Reply(200, exampleResponse()),
        ).use { server ->
            val failure = model(server).ask(request().request) as DecisionOutcome.Failure
            assertThat(failure.failure).isEqualTo(CallFailure.Unavailable)
            assertThat(server.requestCount).isEqualTo(2)
        }
        ScriptedExampleServer.sequence(
            ScriptedExampleServer.Reply(500, "{}"),
            ScriptedExampleServer.Reply(200, exampleResponse()),
        ).use { server ->
            val failure = model(server).ask(request().request) as DecisionOutcome.Failure
            assertThat(failure.failure).isEqualTo(CallFailure.Unavailable)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `deadline mutation guard rejects late success and restored deadline succeeds`() {
        // Changing the short timeout to one second makes the first assertion red. The second request
        // proves that restoring the deadline, rather than a broken fixture, turns the check green.
        ScriptedExampleServer.sequence(
            ScriptedExampleServer.Reply(200, exampleResponse(), Duration.ofMillis(200)),
            ScriptedExampleServer.Reply(200, exampleResponse()),
        ).use { server ->
            val late = model(server).ask(request(Duration.ofMillis(40)).request) as DecisionOutcome.Failure
            assertThat(late.failure).isEqualTo(CallFailure.DeadlineExceeded)
            assertThat(model(server).ask(request(Duration.ofSeconds(1)).request))
                .isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun `interruption cancels the public call and restores the interrupt flag`() {
        ScriptedExampleServer.replying(exampleResponse()).use { server ->
            Thread.currentThread().interrupt()
            try {
                val failure = model(server).ask(request().request) as DecisionOutcome.Failure
                assertThat(failure.failure).isEqualTo(CallFailure.Cancelled)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `outgoing state records and process output exclude secrets without invoking described actions`() {
        val describedActionInvocations = AtomicInteger()
        val builder = DecisionRequest.builder().state(
            mapOf(
                "subject" to "synthetic",
                "api_key" to "STATE_SECRET",
                "nested" to mapOf("password" to "NESTED_SECRET", "safe" to "visible"),
                "tool" to mapOf("name" to "never-call", "invocations" to describedActionInvocations.get()),
            ),
        ).recordPolicy(DecisionRecordPolicy.full(512, setOf("answerIds")))
        val eligible = builder.yesNo("eligible", "Is this item eligible?")
        builder.choice("route", "Which route?", routeOptions())
        builder.rating("urgency", "How urgent?", urgencyOptions())
        ScriptedExampleServer.replying(exampleResponse()).use { server ->
            val success = model(server).ask(builder.build()) as DecisionOutcome.Success
            assertThat((success.answer(eligible) as KeyOutcome.Success).value).isTrue()
            assertThat(describedActionInvocations).hasValue(0)
            assertThat(server.requests.single().body).contains("visible", "never-call")
            assertThat(server.requests.single().body).doesNotContain(
                "STATE_SECRET", "NESTED_SECRET", "api_key", "password", "synthetic-bearer",
            )
            assertThat(success.record!!.fields.values.joinToString()).doesNotContain(
                "STATE_SECRET", "NESTED_SECRET", "synthetic-bearer", "visible", "never-call",
            )
        }

        val originalOut = System.out
        val originalErr = System.err
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            System.setErr(PrintStream(output))
            ScriptedExampleServer.replying(exampleResponse()).use { server ->
                val failure = TypeSafeDecisionModel.create(
                    Supplier { throw IllegalStateException("CREDENTIAL_SECRET") },
                    "requested-example",
                    URI.create(server.baseUri),
                ).ask(request().request) as DecisionOutcome.Failure
                assertThat(failure.failure).isEqualTo(CallFailure.Unavailable)
                assertThat(server.requestCount).isZero()
            }
            ScriptedExampleServer.replying("ERROR_BODY_SECRET", 401).use { server ->
                val failure = model(server).ask(request().request) as DecisionOutcome.Failure
                assertThat(failure.failure).isEqualTo(CallFailure.Unavailable)
                assertThat(failure.record?.fields?.values.orEmpty().joinToString()).doesNotContain("ERROR_BODY_SECRET")
                assertThat(server.requestCount).isEqualTo(1)
            }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        assertThat(output.toString()).doesNotContain("CREDENTIAL_SECRET", "ERROR_BODY_SECRET")
    }

    @Test
    fun `Dice compatibility proof preserves consumer provenance and sends only decision inputs`() {
        val existing = DicePropositionRevisionExample.PropositionState(
            "p-1", "existing proposition", 0.82, "ACTIVE", "source-existing",
        )
        val candidate = DicePropositionRevisionExample.PropositionState(
            "p-2", "candidate proposition", 0.61, "CANDIDATE", "source-candidate",
        )
        val provenance = DecisionProvenance.builder("stub", EvidenceKind.DISTRIBUTION).resolvedModel("stub-v1").build()
        val raw = RawDecisionOutcome.success(
            listOf(
                RawAnswer.distribution(
                    "proposition-relation", DecisionKind.CHOICE,
                    listOf(
                        RawProbability.of("identical", 0.05), RawProbability.of("similar", 0.7),
                        RawProbability.of("unrelated", 0.1), RawProbability.of("contradictory", 0.1),
                        RawProbability.of("generalizes", 0.05),
                    ), "similar",
                ),
            ), provenance,
        )
        val stubAudit = DicePropositionRevisionExample.classify(
            StubDecisionModel.create(listOf(StubStep.immediate(raw))), "revision-42", existing, candidate,
        )
        assertThat(stubAudit.relation()).isEqualTo(DicePropositionRevisionExample.PropositionRelation.SIMILAR)
        assertThat(stubAudit.distribution()).hasSize(5)
        assertThat(stubAudit.decisionProvenance().correlationId).isEqualTo("revision-42")
        assertThat(stubAudit.sourceProvenance()).isEqualTo("source-candidate")
        assertThat(stubAudit.operationId()).isEqualTo("revision-42")
        assertThat(stubAudit.questionId()).isEqualTo("proposition-relation")
        assertThat(stubAudit.existingPropositionId()).isEqualTo("p-1")
        assertThat(stubAudit.candidatePropositionId()).isEqualTo("p-2")
        assertThat(existing).isEqualTo(
            DicePropositionRevisionExample.PropositionState(
                "p-1", "existing proposition", 0.82, "ACTIVE", "source-existing",
            ),
        )
        assertThat(candidate).isEqualTo(
            DicePropositionRevisionExample.PropositionState(
                "p-2", "candidate proposition", 0.61, "CANDIDATE", "source-candidate",
            ),
        )

        ScriptedExampleServer.replying(relationResponse()).use { server ->
            val audit = DicePropositionRevisionExample.classify(model(server), "revision-43", existing, candidate)
            assertThat(audit.relation()).isEqualTo(DicePropositionRevisionExample.PropositionRelation.SIMILAR)
            assertThat(audit.decisionProvenance().resolvedModel).isEqualTo("resolved-example-v1")
            assertThat(audit.operationId()).isEqualTo("revision-43")
            assertThat(audit.questionId()).isEqualTo("proposition-relation")
            assertThat(audit.existingPropositionId()).isEqualTo("p-1")
            assertThat(audit.candidatePropositionId()).isEqualTo("p-2")
            assertThat(server.requests.single().body).contains(
                "proposition-relation", "existingText", "existing proposition",
                "candidateText", "candidate proposition",
            )
            assertThat(server.requests.single().body).doesNotContain(
                "source-candidate", "source-existing", "0.82", "0.61", "ACTIVE", "CANDIDATE", "p-1", "p-2",
            )
        }
    }

    @Test
    fun `owned Java consumers compile independently without unchecked warnings`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val output = Files.createTempDirectory("decision-consumer-java")
        val root = repositoryRoot()
        val sources = listOf(
            root.resolve("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/JevDecisionExample.java"),
            root.resolve("embabel-agent-decisions/embabel-agent-decision-typesafe/src/test/java/com/embabel/agent/decision/example/DicePropositionRevisionExample.java"),
        )
        val exit = compiler.run(
            null, null, null, "-proc:none", "-Xlint:unchecked", "-Werror",
            "-classpath", System.getProperty("java.class.path"), "-d", output.toString(),
            *sources.map { it.toString() }.toTypedArray(),
        )
        assertThat(exit).isZero()
        assertThat(Files.walk(output).use { files -> files.filter { it.toString().endsWith(".class") }.count() })
            .isGreaterThan(0)
    }

    private fun model(server: ScriptedExampleServer) = TypeSafeDecisionModel.create(
        Supplier { "synthetic-bearer" }, "requested-example", URI.create(server.baseUri),
    )

    private fun request(
        timeout: Duration = Duration.ofSeconds(2),
        includeMissing: Boolean = false,
    ): ConsumerRequest {
        val builder = DecisionRequest.builder().state(mapOf("subject" to "synthetic")).timeout(timeout)
        val eligible = builder.yesNo("eligible", "Is this item eligible?")
        val route = builder.choice("route", "Which route?", routeOptions())
        val urgency = builder.rating("urgency", "How urgent?", urgencyOptions())
        val missing = if (includeMissing) builder.yesNo("missing", "Will this be omitted?") else null
        return ConsumerRequest(builder.build(), eligible, route, urgency, missing)
    }

    private fun routeOptions() =
        KotlinRoute.entries.map { DecisionOption.of(it.name.lowercase(), it, it.name.lowercase()) }

    private fun urgencyOptions() =
        KotlinUrgency.entries.map { DecisionOption.of(it.name.lowercase(), it, it.name.lowercase()) }

    private fun repositoryRoot() = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("embabel-agent-decisions")) && Files.exists(it.resolve("embabel-agent-docs")) }

    private data class ConsumerRequest(
        val request: DecisionRequest,
        val eligible: YesNoKey,
        val route: ChoiceKey<KotlinRoute>,
        val urgency: RatingKey<KotlinUrgency>,
        val missing: YesNoKey?,
    )

    companion object {
        private fun assertProvenance(provenance: DecisionProvenance) {
            assertThat(provenance.provider).isEqualTo("typesafe")
            assertThat(provenance.evidenceKind).isEqualTo(EvidenceKind.DISTRIBUTION)
            assertThat(provenance.requestedModel).isEqualTo("requested-example")
            assertThat(provenance.resolvedModel).isEqualTo("resolved-example-v1")
            assertThat(provenance.requestId).isNotBlank()
            assertThat(provenance.questionFingerprint).isNotBlank()
            assertThat(provenance.usage?.inputTokens).isEqualTo(7)
            assertThat(provenance.usage?.outputTokens).isEqualTo(3)
        }

        private fun envelope(answers: String) =
            """{"model":"resolved-example-v1","answers":{$answers},"usage":{"input_tokens":7,"output_tokens":3}}"""

        private fun exampleResponse() = envelope(
            "\"eligible\":{\"type\":\"noul\",\"noul\":0.8}," +
                "\"route\":{\"type\":\"choice\",\"choice\":\"review\",\"confidence\":0.6,\"probabilities\":{\"accept\":0.2,\"review\":0.6,\"reject\":0.2}}," +
                "\"urgency\":{\"type\":\"score\",\"score\":1.6,\"confidence\":0.75,\"legend\":{\"0\":\"low\",\"1\":\"medium\",\"2\":\"high\"},\"probabilities\":{\"0\":0.1,\"1\":0.2,\"2\":0.7}}",
        )

        private fun relationResponse() =
            """{"model":"resolved-example-v1","answers":{"proposition-relation":{"type":"choice","choice":"similar","confidence":0.7,"probabilities":{"identical":0.05,"similar":0.7,"unrelated":0.1,"contradictory":0.1,"generalizes":0.05}}},"usage":{"input_tokens":5,"output_tokens":2}}"""
    }
}

private class ScriptedExampleServer private constructor(private val replies: List<Reply>) : AutoCloseable {
    data class Reply(val status: Int, val body: String, val delay: Duration = Duration.ZERO)
    data class Captured(val method: String, val path: String, val authorization: String?, val body: String)

    private val cursor = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<Captured>()
    val requestCount: Int get() = cursor.get()
    val baseUri: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange -> respond(exchange) }
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        val reply = replies[minOf(cursor.getAndIncrement(), replies.lastIndex)]
        requests += Captured(
            exchange.requestMethod,
            exchange.requestURI.path,
            exchange.requestHeaders.getFirst("Authorization"),
            exchange.requestBody.readBytes().decodeToString(),
        )
        if (!reply.delay.isZero) Thread.sleep(reply.delay.toMillis())
        val bytes = reply.body.toByteArray()
        try {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: java.io.IOException) {
            // The deadline check deliberately closes the client side before the late response is written.
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun replying(body: String, status: Int = 200) = sequence(Reply(status, body))
        fun sequence(vararg replies: Reply) = ScriptedExampleServer(replies.toList())
    }
}
