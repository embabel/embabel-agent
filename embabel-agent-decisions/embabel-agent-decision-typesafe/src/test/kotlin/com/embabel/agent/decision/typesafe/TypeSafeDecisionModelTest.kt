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
package com.embabel.agent.decision.typesafe

import com.embabel.agent.decision.DecisionOption
import com.embabel.agent.decision.DecisionOutcome
import com.embabel.agent.decision.DecisionRecordPolicy
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.KeyOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLClassLoader
import java.lang.reflect.Modifier
import java.util.function.Supplier
import javax.tools.ToolProvider

class TypeSafeDecisionModelTest {
    @Test
    fun `maps a redacted typed request and resolved provenance through the final facade`() {
        CaptureServer.replying(resource("success.json")).use { server ->
            val built = request()
            val outcome = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri))
                .ask(built.request)
            val success = outcome as DecisionOutcome.Success

            assertThat(server.requestCount).isEqualTo(1)
            assertThat(server.requests.single().method).isEqualTo("POST")
            assertThat(server.requests.single().path).isEqualTo("/v1/systemone")
            assertThat(server.requests.single().headers["Authorization"]?.single()).isEqualTo("Bearer synthetic-bearer")
            assertThat(server.requests.single().headers.entries.first { it.key.equals("Content-Type", true) }.value).contains("application/json")
            assertThat(server.requests.single().headers.entries.first { it.key.equals("Accept", true) }.value).contains("application/json")
            assertThat(server.requests.single().body).isEqualTo("""{"state":{"text":"synthetic"},"model":"requested-test","questions":{"q_yes":{"type":"noul","instructions":"is this synthetic?","criteria":{"true":"true","false":"false"}},"q_choice":{"type":"choice","instructions":"pick one","criteria":{"s_a":"A","s_b":"B"}},"q_rating":{"type":"score","instructions":"rate it","criteria":["low","high"]}}}""")
            assertThat(server.requests.single().body).doesNotContain("token", "do-not-send")
            assertThat(success.provenance.requestedModel).isEqualTo("requested-test")
            assertThat(success.provenance.resolvedModel).isEqualTo("resolved-test-v1")
            assertThat(success.provenance.usage?.inputTokens).isEqualTo(7)
            assertThat(success.answer(built.choice)).isInstanceOf(KeyOutcome.Success::class.java)
            assertThat((success.answer(built.choice) as KeyOutcome.Success).value).isEqualTo("A")
            assertThat((success.answer(built.choice) as KeyOutcome.Success).distribution).containsEntry("A", 0.5)
            assertThat((success.answer(built.rating) as KeyOutcome.Success).expectedScore).isEqualTo(0.75)
        }
    }

    @Test
    fun `is Java callable without exposing an alternate model type`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val source = kotlin.io.path.createTempDirectory("typesafe-java-probe").toFile()
        val java = source.resolve("Probe.java")
        java.writeText("""
            import com.embabel.agent.decision.*;
            import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel;
            import java.net.URI;
            import java.util.function.Supplier;
            public class Probe {
              public static void run(String baseUri) {
                DecisionModel model = TypeSafeDecisionModel.create((Supplier<String>) () -> "x", "model", URI.create(baseUri));
                DecisionRequest.Builder builder = DecisionRequest.builder();
                YesNoKey key = builder.yesNo("q", "question");
                DecisionOutcome outcome = model.ask(builder.build());
                if (!(outcome instanceof DecisionOutcome.Success)) throw new AssertionError();
              }
            }
        """.trimIndent())
        CaptureServer.replying("""{"model":"resolved-java","answers":{"q":{"type":"noul","noul":1.0}},"usage":{"input_tokens":1,"output_tokens":1}}""").use { server ->
            val exit = compiler.run(null, null, null, "-Xlint:unchecked", "-Werror", "-classpath", System.getProperty("java.class.path"), "-d", source.path, java.path)
            assertThat(exit).isZero()
            URLClassLoader(arrayOf(source.toURI().toURL()), javaClass.classLoader).use { loader ->
                loader.loadClass("Probe").getMethod("run", String::class.java).invoke(null, server.baseUri)
            }
        }
    }

    @Test
    fun `uses facade record projection and never places state or evidence in records`() {
        CaptureServer.replying(resource("success.json")).use { server ->
            val none = request(DecisionRecordPolicy.none())
            val noneOutcome = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(none.request) as DecisionOutcome.Success
            assertThat(noneOutcome.record).isNull()
        }
        CaptureServer.replying(resource("success.json")).use { server ->
            val metadata = request(DecisionRecordPolicy.metadata())
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(metadata.request) as DecisionOutcome.Success
            assertThat(result.record!!.fields.values.joinToString()).doesNotContain("synthetic", "do-not-send", "0.75", "A", "LOW")
        }
        CaptureServer.replying(resource("success.json")).use { server ->
            val full = request(DecisionRecordPolicy.full(256, setOf("answerIds")))
            val result = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri)).ask(full.request) as DecisionOutcome.Success
            assertThat(result.record!!.fields["answerIds"]).contains("q_yes", "q_choice", "q_rating")
            assertThat(result.record!!.fields.values.joinToString()).doesNotContain("do-not-send", "synthetic-bearer")
        }
    }

    @Test
    fun `keeps only the final decision facade as its public model surface`() {
        CaptureServer.replying(resource("success.json")).use { server ->
            val model = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri))
            assertThat(model.javaClass).isEqualTo(DecisionOutcome::class.java.classLoader.loadClass("com.embabel.agent.decision.DecisionModel"))
        }
        assertThat(Modifier.isFinal(com.embabel.agent.decision.DecisionModel::class.java.modifiers)).isTrue()
        assertThat(JevTransport::class.java.methods.map { it.name }).doesNotContain("ask", "create")
    }

    @Test
    fun `accepts the literal IPv6 loopback origin without resolving credentials`() {
        var credentialCalls = 0
        val model = TypeSafeDecisionModel.create(Supplier { credentialCalls++; "synthetic-bearer" }, "requested-test", URI.create("http://[::1]:8080/"))
        assertThat(model).isNotNull()
        assertThat(credentialCalls).isZero()
    }

    @Test
    fun `rejects non-loopback HTTP origins and lookalikes`() {
        listOf("http://[::2]:8080/", "http://127.0.0.2:8080/", "http://localhost:8080/", "http://127.0.0.1:8080/not-an-origin").forEach { origin ->
            assertThatThrownBy { TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(origin)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("base URI must be an HTTPS origin")
        }
    }

    private fun request(policy: DecisionRecordPolicy? = null): BuiltRequest {
        val builder = DecisionRequest.builder().state(mapOf("token" to "do-not-send", "text" to "synthetic"))
        policy?.let(builder::recordPolicy)
        val yes = builder.yesNo("q_yes", "is this synthetic?")
        val choice = builder.choice("q_choice", "pick one", listOf(
            DecisionOption.of("s_a", "A", "A"), DecisionOption.of("s_b", "B", "B")
        ))
        val rating = builder.rating("q_rating", "rate it", listOf(
            DecisionOption.of("low", "LOW", "low"), DecisionOption.of("high", "HIGH", "high")
        ))
        return BuiltRequest(builder.build(), yes, choice, rating)
    }

    private fun resource(name: String): String = javaClass.getResource("/decision/typesafe/$name")!!.readText()
    private data class BuiltRequest(
        val request: DecisionRequest,
        val yes: com.embabel.agent.decision.YesNoKey,
        val choice: com.embabel.agent.decision.ChoiceKey<String>,
        val rating: com.embabel.agent.decision.RatingKey<String>,
    )
}
