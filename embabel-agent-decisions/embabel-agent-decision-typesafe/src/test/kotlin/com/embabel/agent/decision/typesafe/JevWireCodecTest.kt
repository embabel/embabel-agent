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

import com.embabel.agent.decision.api.typesafe.TypeSafeDecisionModel
import com.embabel.agent.decision.api.CallFailure
import com.embabel.agent.decision.api.DecisionOption
import com.embabel.agent.decision.api.DecisionOutcome
import com.embabel.agent.decision.api.DecisionRequest
import com.embabel.agent.decision.api.KeyFailure
import com.embabel.agent.decision.api.KeyOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.util.function.Supplier

/** Fixtures are synthetic wire examples, retained byte-for-byte to exercise duplicate-property handling. */
class JevWireCodecTest {
    @Test
    fun `preserves arbitrary precision state numbers in the captured JSON request`() {
        val integer = BigInteger("900719925474099312345678901234567890")
        val decimal = BigDecimal("1234567890.123456789012345678901234567890")
        CaptureServer.replying(success()).use { server ->
            val result = model(server).ask(request(mapOf("integer" to integer, "decimal" to decimal)).request)

            assertThat(result).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(server.requests).hasSize(1)
            assertThat(server.requests.single().body)
                .contains("\"integer\":$integer")
                .contains("\"decimal\":${decimal.toPlainString()}")
                .doesNotContain("9.007199254740993E")
        }
    }

    @Test
    fun `writes every finite primitive number as a JSON number without changing its value`() {
        CaptureServer.replying(success()).use { server ->
            val result = model(server).ask(request(linkedMapOf(
                "byte" to 7.toByte(),
                "short" to 32000.toShort(),
                "int" to 2_000_000_001,
                "long" to 9_007_199_254_740_993L,
                "float" to 1.25f,
                "double" to -42.125,
            )).request)

            assertThat(result).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(server.requests.single().body).contains(
                "\"byte\":7",
                "\"short\":32000",
                "\"int\":2000000001",
                "\"long\":9007199254740993",
                "\"float\":1.25",
                "\"double\":-42.125",
            )
        }
    }

    @Test
    fun `rejects non finite state numbers before opening the transport`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Float.NaN, Float.POSITIVE_INFINITY)
            .forEach { value ->
                CaptureServer.replying(success()).use { server ->
                    val result = model(server).ask(request(mapOf("invalid" to value)).request) as DecisionOutcome.Failure

                    assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
                    assertThat(server.requestCount).isZero()
                }
            }
    }

    @Test
    fun `rejects duplicate wire answer ids without retaining the validation body`() {
        CaptureServer.replying(resource("duplicate-answer.json")).use { server ->
            val result = model(server).ask(request().request) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
            assertThat(result.record?.fields?.values.orEmpty().joinToString()).doesNotContain("SECRET_DO_NOT_LOG")
        }
    }

    @Test
    fun `preserves a valid sibling when a known answer uses a future discriminator`() {
        CaptureServer.replying(resource("unknown-type.json")).use { server ->
            val built = request()
            val result = model(server).ask(built.request) as DecisionOutcome.Success
            assertThat((result.answer(built.yes) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Unsupported)
            assertThat(result.answer(built.choice)).isInstanceOf(KeyOutcome.Success::class.java)
            assertThat((result.answer(built.rating) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
        }
    }

    @Test
    fun `maps a validation envelope to a safe rejection`() {
        CaptureServer.replying(resource("validation-error.json"), 422).use { server ->
            val result = model(server).ask(request().request) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
            assertThat(result.record?.fields?.values.orEmpty().joinToString()).doesNotContain("SECRET_DO_NOT_LOG")
        }
    }

    @Test
    fun `keeps valid siblings when a choice selection is not a maximizer`() {
        val body = """{"model":"resolved-test-v1","answers":{"q_yes":{"type":"noul","noul":0.75},"q_choice":{"type":"choice","choice":"s_b","confidence":0.5,"probabilities":{"s_a":0.7,"s_b":0.3}}},"usage":{"input_tokens":1,"output_tokens":1}}"""
        CaptureServer.replying(body).use { server ->
            val built = request()
            val result = model(server).ask(built.request) as DecisionOutcome.Success
            assertThat(result.answer(built.yes)).isInstanceOf(KeyOutcome.Success::class.java)
            assertThat((result.answer(built.choice) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Invalid)
        }
    }

    @Test
    fun `accepts rating probabilities and legends in semantic rather than JSON field order`() {
        val body = """{"model":"resolved-test-v1","answers":{"q_rating":{"type":"score","score":0.75,"confidence":0.75,"legend":{"1":"high","0":"low"},"probabilities":{"1":0.75,"0":0.25}}},"usage":{"input_tokens":1,"output_tokens":1}}"""
        CaptureServer.replying(body).use { server ->
            val built = request()
            val result = model(server).ask(built.request) as DecisionOutcome.Success
            val rating = result.answer(built.rating) as KeyOutcome.Success
            assertThat(rating.expectedScore).isEqualTo(0.75)
            assertThat(rating.value).isEqualTo("HIGH")
        }
    }

    @Test
    fun `keeps declared rating tie order and does not treat score as a selected level`() {
        val body = """{"model":"resolved-test-v1","answers":{"q_rating":{"type":"score","score":0.5,"confidence":0.5,"legend":{"0":"low","1":"high"},"probabilities":{"1":0.5,"0":0.5}}},"usage":{"input_tokens":1,"output_tokens":1}}"""
        CaptureServer.replying(body).use { server ->
            val built = request()
            val rating = (model(server).ask(built.request) as DecisionOutcome.Success).answer(built.rating) as KeyOutcome.Success
            assertThat(rating.expectedScore).isEqualTo(0.5)
            assertThat(rating.maximizers).containsExactly("LOW", "HIGH")
            assertThat(rating.firstMaximizer).isEqualTo("LOW")
        }
    }

    @Test
    fun `rejects malformed envelopes including unsafe resolved models`() {
        listOf(
            "{",
            """{"model":"","answers":{},"usage":{"input_tokens":0,"output_tokens":0}}""",
            """{"model":"line\nbreak","answers":{},"usage":{"input_tokens":0,"output_tokens":0}}""",
            """{"model":"${"x".repeat(257)}","answers":{},"usage":{"input_tokens":0,"output_tokens":0}}""",
            """{"model":"resolved","answers":{}}""",
            """{"model":"resolved","answers":{},"usage":{"input_tokens":-1,"output_tokens":0}}""",
            """{"model":"resolved","answers":{},"usage":{"input_tokens":1.5,"output_tokens":0}}""",
            """{"model":"resolved","answers":{"unknown":{"type":"noul","noul":1}},"usage":{"input_tokens":0,"output_tokens":0}}""",
        ).forEach { body ->
            CaptureServer.replying(body).use { server ->
                val result = model(server).ask(request().request) as DecisionOutcome.Failure
                assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
            }
        }
    }

    @Test
    fun `isolates malformed recognizable answers while rejecting duplicate wire structures`() {
        val invalidRatingBodies = listOf(
            """{"q_rating":{"type":"score","score":0.75,"confidence":0.5,"legend":{"0":"low","0":"low","1":"high"},"probabilities":{"0":0.25,"1":0.75}}}""",
            """{"q_rating":{"type":"score","score":0.75,"confidence":0.5,"legend":{"00":"low","1":"high"},"probabilities":{"0":0.25,"1":0.75}}}""",
            """{"q_rating":{"type":"score","score":0.75,"confidence":0.5,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.25,"0":0.75}}}""",
            """{"q_rating":{"type":"score","score":0.75,"confidence":1.1,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.25,"1":0.75}}}""",
            """{"q_rating":{"type":"score","score":0.75,"confidence":0.5,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.2,"1":0.7}}}""",
        )
        invalidRatingBodies.forEach { answer ->
            val rating = answer.removePrefix("{\"q_rating\":").removeSuffix("}")
            val body = """{"model":"resolved","answers":{"q_yes":{"type":"noul","noul":1.0},"q_rating":$rating},"usage":{"input_tokens":0,"output_tokens":0}}"""
            CaptureServer.replying(body).use { server ->
                val built = request()
                val result = model(server).ask(built.request) as DecisionOutcome.Success
                assertThat(result.answer(built.yes)).isInstanceOf(KeyOutcome.Success::class.java)
                assertThat((result.answer(built.rating) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Invalid)
            }
        }
        val mismatch = """{"model":"resolved","answers":{"q_yes":{"type":"choice","choice":"s_a","confidence":1,"probabilities":{"s_a":1}}},"usage":{"input_tokens":0,"output_tokens":0}}"""
        CaptureServer.replying(mismatch).use { server ->
            val built = request()
            val result = model(server).ask(built.request) as DecisionOutcome.Success
            assertThat((result.answer(built.yes) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Invalid)
        }
    }

    @Test
    fun `rejects extra and duplicate top level ids while missing answers stay missing`() {
        val extra = """{"model":"resolved","answers":{"q_yes":{"type":"noul","noul":1},"extra":{"type":"noul","noul":1}},"usage":{"input_tokens":0,"output_tokens":0}}"""
        CaptureServer.replying(extra).use { server ->
            val result = model(server).ask(request().request) as DecisionOutcome.Failure
            assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
        }
        val missing = """{"model":"resolved","answers":{"q_yes":{"type":"noul","noul":1}},"usage":{"input_tokens":0,"output_tokens":0}}"""
        CaptureServer.replying(missing).use { server ->
            val built = request()
            val result = model(server).ask(built.request) as DecisionOutcome.Success
            assertThat((result.answer(built.choice) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
            assertThat((result.answer(built.rating) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
        }
    }

    @Test
    fun `caller mapper features cannot change request bytes or loosen response parsing`() {
        val shared = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS)
            .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            .build()
        val holder = com.embabel.common.util.EmbabelObjectMapperHolder(shared)
        val expected = """{"state":{"value":7},"model":"requested-test","questions":{"q_yes":{"type":"noul","instructions":"is this synthetic?","criteria":{"true":"true","false":"false"}},"q_choice":{"type":"choice","instructions":"pick one","criteria":{"s_a":"A","s_b":"B"}},"q_rating":{"type":"score","instructions":"rate it","criteria":["low","high"]}}}"""

        CaptureServer.replying(success()).use { server ->
            val outcome = TypeSafeDecisionModel.create(
                Supplier { "synthetic-bearer" },
                "requested-test",
                URI.create(server.baseUri),
                java.time.Duration.ofSeconds(10),
                holder,
            ).ask(request(mapOf("value" to 7)).request)
            assertThat(outcome).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(server.requests.single().body).isEqualTo(expected)
        }
        listOf(
            """{"model":"resolved","answers":{},"usage":{"input_tokens":0,"output_tokens":0,},}""",
            """{"model":"resolved","answers":{"q_yes":{"type":"noul","noul":NaN}},"usage":{"input_tokens":0,"output_tokens":0}}""",
        ).forEach { body ->
            CaptureServer.replying(body).use { server ->
                val outcome = TypeSafeDecisionModel.create(
                    Supplier { "synthetic-bearer" },
                    "requested-test",
                    URI.create(server.baseUri),
                    java.time.Duration.ofSeconds(10),
                    holder,
                ).ask(request().request) as DecisionOutcome.Failure
                assertThat(outcome.failure).isEqualTo(CallFailure.RejectedRequest)
            }
        }
        assertThat(shared.writeValueAsString("a_b")).isEqualTo("\"a_b\"")
    }

    private fun model(server: CaptureServer) = TypeSafeDecisionModel.create(Supplier { "synthetic-bearer" }, "requested-test", URI.create(server.baseUri))
    private fun resource(name: String): String = javaClass.getResource("/decision/typesafe/$name")!!.readText()
    private fun request(state: Map<String, Any?> = mapOf("text" to "synthetic")): WireRequest {
        val builder = DecisionRequest.builder().state(state)
        val yes = builder.yesNo("q_yes", "is this synthetic?")
        val choice = builder.choice("q_choice", "pick one", listOf(DecisionOption.of("s_a", "A", "A"), DecisionOption.of("s_b", "B", "B")))
        val rating = builder.rating("q_rating", "rate it", listOf(DecisionOption.of("low", "LOW", "low"), DecisionOption.of("high", "HIGH", "high")))
        return WireRequest(builder.build(), yes, choice, rating)
    }
    private data class WireRequest(
        val request: DecisionRequest,
        val yes: com.embabel.agent.decision.api.YesNoKey,
        val choice: com.embabel.agent.decision.api.ChoiceKey<String>,
        val rating: com.embabel.agent.decision.api.RatingKey<String>,
    )

    private fun success() = """{"model":"resolved-test-v1","answers":{"q_yes":{"type":"noul","noul":0.75}},"usage":{"input_tokens":1,"output_tokens":1}}"""
}
