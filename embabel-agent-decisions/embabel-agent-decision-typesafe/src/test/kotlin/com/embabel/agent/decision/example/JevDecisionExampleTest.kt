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

import com.embabel.agent.decision.DecisionOption
import com.embabel.agent.decision.DecisionProvenance
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.EvidenceKind
import com.embabel.agent.decision.RawAnswer
import com.embabel.agent.decision.RawDecisionOutcome
import com.embabel.agent.decision.RawProbability
import com.embabel.agent.decision.StubDecisionModel
import com.embabel.agent.decision.StubStep
import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.tools.ToolProvider

class JevDecisionExampleTest {
    @Test
    fun `both language consumers execute the real facade and local TypeSafe adapter`() {
        ExampleServer().use { server ->
            val model = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-example", URI.create(server.baseUri))
            val kotlin = runKotlinDecision(model)
            val java = JevDecisionExample.run(model)

            assertThat(kotlin.route).isEqualTo(KotlinRoute.REVIEW)
            assertThat(kotlin.urgency).isEqualTo(KotlinUrgency.HIGH)
            assertThat(java.route()).isEqualTo(JevDecisionExample.Route.REVIEW)
            assertThat(java.urgency()).isEqualTo(JevDecisionExample.Urgency.HIGH)
            assertThat(kotlin.provenance.resolvedModel).isEqualTo("resolved-example-v1")
            assertThat(server.count.get()).isEqualTo(2)
            server.requests.forEach { request ->
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.path).isEqualTo("/v1/systemone")
                assertThat(request.authorization).isEqualTo("Bearer synthetic-bearer")
                assertThat(request.body).contains("\"type\":\"noul\"", "\"type\":\"choice\"", "\"type\":\"score\"")
                assertThat(request.body).doesNotContain("synthetic-bearer")
            }
        }
    }

    @Test
    fun `Dice compatibility proof works through stub and local TypeSafe without mutating proposition state`() {
        val existing = DicePropositionRevisionExample.PropositionState("p-1", 0.82, "ACTIVE", "source-existing")
        val candidate = DicePropositionRevisionExample.PropositionState("p-2", 0.61, "CANDIDATE", "source-candidate")
        val provenance = DecisionProvenance.builder("stub", EvidenceKind.DISTRIBUTION).resolvedModel("stub-v1").build()
        val raw = RawDecisionOutcome.success(
            listOf(RawAnswer.distribution("proposition-relation", com.embabel.agent.decision.DecisionKind.CHOICE,
                listOf(
                    RawProbability.of("identical", 0.05), RawProbability.of("similar", 0.7),
                    RawProbability.of("unrelated", 0.1), RawProbability.of("contradictory", 0.1),
                    RawProbability.of("generalizes", 0.05),
                ), "similar")), provenance,
        )
        val stubAudit = DicePropositionRevisionExample.classify(
            StubDecisionModel.create(listOf(StubStep.immediate(raw))), "revision-42", existing, candidate,
        )
        assertThat(stubAudit.relation()).isEqualTo(DicePropositionRevisionExample.PropositionRelation.SIMILAR)
        assertThat(stubAudit.distribution()).hasSize(5)
        assertThat(stubAudit.decisionProvenance().correlationId).isEqualTo("revision-42")
        assertThat(stubAudit.sourceProvenance()).isEqualTo("source-candidate")
        assertThat(existing).isEqualTo(DicePropositionRevisionExample.PropositionState("p-1", 0.82, "ACTIVE", "source-existing"))
        assertThat(candidate).isEqualTo(DicePropositionRevisionExample.PropositionState("p-2", 0.61, "CANDIDATE", "source-candidate"))

        ExampleServer().use { server ->
            val model = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-example", URI.create(server.baseUri))
            val audit = DicePropositionRevisionExample.classify(model, "revision-43", existing, candidate)
            assertThat(audit.relation()).isEqualTo(DicePropositionRevisionExample.PropositionRelation.SIMILAR)
            assertThat(audit.decisionProvenance().resolvedModel).isEqualTo("resolved-example-v1")
            assertThat(server.requests.single().body).contains("proposition-relation", "existingId", "candidateId")
            assertThat(server.requests.single().body).doesNotContain("source-candidate", "source-existing", "0.82", "0.61")
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
        val exit = compiler.run(null, null, null, "-proc:none", "-Xlint:unchecked", "-Werror",
            "-classpath", System.getProperty("java.class.path"), "-d", output.toString(), *sources.map { it.toString() }.toTypedArray())
        assertThat(exit).isZero()
        assertThat(Files.walk(output).use { files -> files.filter { it.toString().endsWith(".class") }.count() }).isGreaterThan(0)
    }

    private fun repositoryRoot() = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("embabel-agent-decisions")) && Files.exists(it.resolve("embabel-agent-docs")) }
}

private class ExampleServer : AutoCloseable {
    data class Captured(val method: String, val path: String, val authorization: String?, val body: String)
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val count = AtomicInteger()
    val requests = mutableListOf<Captured>()
    val baseUri: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            synchronized(requests) { requests += Captured(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body) }
            count.incrementAndGet()
            val response = if (body.contains("proposition-relation")) relationResponse() else exampleResponse()
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() { server.stop(0); executor.shutdownNow() }

    private fun exampleResponse() = """{"model":"resolved-example-v1","answers":{"eligible":{"type":"noul","noul":0.8},"route":{"type":"choice","choice":"review","confidence":0.6,"probabilities":{"accept":0.2,"review":0.6,"reject":0.2}},"urgency":{"type":"score","score":1.6,"confidence":0.75,"legend":{"0":"low","1":"medium","2":"high"},"probabilities":{"0":0.1,"1":0.2,"2":0.7}}},"usage":{"input_tokens":7,"output_tokens":3}}"""
    private fun relationResponse() = """{"model":"resolved-example-v1","answers":{"proposition-relation":{"type":"choice","choice":"similar","confidence":0.7,"probabilities":{"identical":0.05,"similar":0.7,"unrelated":0.1,"contradictory":0.1,"generalizes":0.05}}},"usage":{"input_tokens":5,"output_tokens":2}}"""
}
