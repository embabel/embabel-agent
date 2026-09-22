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
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.KeyOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLClassLoader
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
            assertThat(server.requests.single().body).contains("q_yes", "q_choice", "q_rating")
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

    private fun request(): BuiltRequest {
        val builder = DecisionRequest.builder().state(mapOf("token" to "do-not-send", "text" to "synthetic"))
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
