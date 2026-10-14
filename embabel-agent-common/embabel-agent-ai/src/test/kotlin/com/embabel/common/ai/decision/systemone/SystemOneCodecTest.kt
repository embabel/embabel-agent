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
package com.embabel.common.ai.decision.systemone

import com.embabel.common.ai.classification.CategoryProbability
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.LevelProbability
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.RatingScore
import com.embabel.common.ai.decision.RatingStatistic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper

class SystemOneCodecTest {

    private val mapper = JsonMapper.builder().build()

    private val urgent = Questions.named("urgent").proposition("Is this urgent?").build()
    private val team = Questions.named("team").choice("Which team?")
        .option("billing", "Payments").option("technical", "Outages").build()
    private val anger = Questions.named("anger").rating("How angry?")
        .level("calm", "Calm").level("annoyed", "").level("furious", "Furious").build()
    private val spec = DecisionSpec.of(urgent, team, anger)

    private fun provenance(model: String?) = ModelProvenance(model ?: "jev-latest", "TypeSafe", requestId = "req-1")

    private fun decode(json: String, spec: DecisionSpec = this.spec): DecisionResponse =
        SystemOneCodec.decode(json.toByteArray(), spec, "jev-latest", ::provenance)

    private fun body(vararg answers: String) =
        """{"model":"jev-1","answers":{${answers.joinToString(",")}},"usage":{"input_tokens":3,"output_tokens":0}}"""

    private val noul = """"q1":{"type":"noul","noul":0.86}"""
    private val choice = """"q2":{"type":"choice","choice":"billing","probabilities":{"billing":0.8,"technical":0.2},"confidence":0.4}"""
    private val score = """"q3":{"type":"score","score":1.5,"legend":{"0":"Calm","1":"annoyed","2":"Furious"},"probabilities":{"0":0.1,"1":0.3,"2":0.6},"confidence":0.2}"""

    @Nested
    inner class Encoding {

        @Test
        fun `questions are keyed q1 to qN in spec order with the state and model`() {
            val json = mapper.readTree(SystemOneCodec.encode("jev-latest", DecisionRequest.of("charged twice", spec)))
            assertEquals("charged twice", json.get("state").stringValue())
            assertEquals("jev-latest", json.get("model").stringValue())
            assertEquals(listOf("q1", "q2", "q3"), json.get("questions").propertyNames().toList())
            assertEquals(
                mapper.readTree("""{"type":"noul","instructions":"Is this urgent?"}"""),
                json.get("questions").get("q1"),
            )
            assertEquals(
                mapper.readTree("""{"type":"choice","instructions":"Which team?","criteria":{"billing":"Payments","technical":"Outages"}}"""),
                json.get("questions").get("q2"),
            )
        }

        @Test
        fun `score criteria list level descriptions and fall back to the level id`() {
            val json = mapper.readTree(SystemOneCodec.encode("jev-latest", DecisionRequest.of("x", DecisionSpec.of(anger))))
            assertEquals(
                mapper.readTree("""{"type":"score","instructions":"How angry?","criteria":["Calm","annoyed","Furious"]}"""),
                json.get("questions").get("q1"),
            )
        }

        @Test
        fun `a body without a model holds only the state and questions`() {
            val json = mapper.readTree(SystemOneCodec.encode(DecisionRequest.of("charged twice", spec)))
            assertEquals(listOf("state", "questions"), json.propertyNames().toList())
            assertEquals(listOf("q1", "q2", "q3"), json.get("questions").propertyNames().toList())
        }

        @Test
        fun `caller question names never reach the wire`() {
            val json = String(SystemOneCodec.encode("jev-latest", DecisionRequest.of("x", spec)))
            listOf("urgent", "team", "anger").forEach { assertFalse(json.contains("\"$it\""), json) }
        }
    }

    @Nested
    inner class Envelope {

        @Test
        fun `an unusable envelope fails the whole request`() {
            listOf(
                "not json",
                "null",
                "{}",
                """{"model":"jev-1","answers":{}}""",
                """{"model":"jev-1","answers":[]}""",
                body(noul) + " trailing",
                """{"model":"jev-1","answers":{$noul,$noul}}""",
            ).forEach { json ->
                assertThrows<SystemOneFormatException>(json) { decode(json) }
            }
        }

        @Test
        fun `a format failure message never holds response content`() {
            val e = assertThrows<SystemOneFormatException> { decode("""{"model":"SENTINEL","answers":"SENTINEL"}""") }
            assertFalse(e.message!!.contains("SENTINEL"))
        }

        @Test
        fun `unknown members are ignored for forward compatibility`() {
            val response = decode("""{"model":"jev-1","extra":1,"answers":{"q1":{"type":"noul","noul":0.9,"note":"x"}}}""", DecisionSpec.of(urgent))
            assertEquals(PropositionResult.Answered(true, provenance("jev-1"), 0.9), response.answer(urgent))
        }

        @Test
        fun `a blank response model falls back to the requested model`() {
            val response = decode("""{"model":" ","answers":{$noul}}""", DecisionSpec.of(urgent))
            assertEquals(provenance(null), (response.answer(urgent) as PropositionResult.Answered).provenance)
        }
    }

    @Nested
    inner class Propositions {

        @Test
        fun `exactly one half is inconclusive and other values answer by threshold`() {
            fun answer(p: String) = decode("""{"model":"jev-1","answers":{"q1":{"type":"noul","noul":$p}}}""", DecisionSpec.of(urgent))
                .answer(urgent)
            assertEquals(PropositionResult.Inconclusive(provenance("jev-1")), answer("0.5"))
            assertEquals(PropositionResult.Answered(false, provenance("jev-1"), 0.2), answer("0.2"))
            assertEquals(PropositionResult.Answered(true, provenance("jev-1"), 1.0), answer("1"))
        }

        @Test
        fun `out of range, missing and non-numeric values fail only that question`() {
            listOf("1.5", "-0.1", "1e999", "\"0.5\"", "null").forEach { p ->
                val response = decode(body(""""q1":{"type":"noul","noul":$p}""", choice, score))
                assertEquals(PropositionResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(urgent), p)
                assertTrue(response.answer(team) is ClassificationResult.Selected, p)
            }
        }
    }

    @Nested
    inner class Choices {

        @Test
        fun `a selection keeps the distribution in option order and the confidence`() {
            val response = decode(body(noul, """"q2":{"type":"choice","choice":"billing","probabilities":{"technical":0.2,"billing":0.8},"confidence":0.4}""", score))
            assertEquals(
                ClassificationResult.Selected(
                    "billing", provenance("jev-1"), 0.4,
                    listOf(CategoryProbability("billing", 0.8), CategoryProbability("technical", 0.2)),
                ),
                response.answer(team),
            )
        }

        @Test
        fun `zero confidence is inconclusive`() {
            val response = decode(body(noul, """"q2":{"type":"choice","choice":"billing","probabilities":{"billing":0.5,"technical":0.5},"confidence":0.0}""", score))
            assertEquals(ClassificationResult.Inconclusive(provenance("jev-1")), response.answer(team))
        }

        @Test
        fun `bad distributions, out of domain choices and missing labels fail only that question`() {
            listOf(
                """{"type":"choice","choice":"billing","probabilities":{"billing":1.0},"confidence":0.4}""",
                """{"type":"choice","choice":"billing","probabilities":{"billing":0.8,"technical":0.3},"confidence":0.4}""",
                """{"type":"choice","choice":"sales","probabilities":{"billing":0.8,"technical":0.2},"confidence":0.4}""",
                """{"type":"choice","probabilities":{"billing":0.8,"technical":0.2},"confidence":0.4}""",
                """{"type":"choice","choice":"billing","probabilities":{"billing":0.8,"technical":0.2},"confidence":1.4}""",
            ).forEach { answer ->
                val response = decode(body(noul, """"q2":$answer""", score))
                assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(team), answer)
                assertTrue(response.answer(urgent) is PropositionResult.Answered, answer)
            }
        }
    }

    @Nested
    inner class Ratings {

        @Test
        fun `legend labels do not remap the requested level order`() {
            val reversed = score.replace(
                "\"0\":\"Calm\",\"1\":\"annoyed\",\"2\":\"Furious\"",
                "\"0\":\"Furious\",\"1\":\"reworded\",\"2\":\"Calm\"",
            )
            val result = decode(body(noul, choice, reversed)).answer(anger) as RatingResult.Answered

            assertEquals(
                listOf(LevelProbability("calm", 0.1), LevelProbability("annoyed", 0.3), LevelProbability("furious", 0.6)),
                result.distribution,
            )
            assertEquals(RatingScore(1.5, RatingStatistic.EXPECTED_LEVEL_INDEX), result.score)
        }

        @Test
        fun `a score becomes the level distribution and expected level index`() {
            val response = decode(body(noul, choice, score))
            assertEquals(
                RatingResult.Answered(
                    provenance("jev-1"),
                    null,
                    listOf(LevelProbability("calm", 0.1), LevelProbability("annoyed", 0.3), LevelProbability("furious", 0.6)),
                    RatingScore(1.5, RatingStatistic.EXPECTED_LEVEL_INDEX),
                    0.2,
                ),
                response.answer(anger),
            )
        }

        @Test
        fun `a wrong legend, bad distribution or out of range score fails only that question`() {
            listOf(
                """{"type":"score","score":1.5,"legend":{"0":"a","1":"b","2":"c"},"confidence":0.2}""",
                """{"type":"score","score":1.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":null,"confidence":0.2}""",
                """{"type":"score","score":1.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":[],"confidence":0.2}""",
                """{"type":"score","score":1.5,"legend":{"0":"a","1":"b"},"probabilities":{"0":0.1,"1":0.3,"2":0.6},"confidence":0.2}""",
                """{"type":"score","score":1.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"0":0.1,"1":0.3,"2":0.7},"confidence":0.2}""",
                """{"type":"score","score":2.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"0":0.1,"1":0.3,"2":0.6},"confidence":0.2}""",
            ).forEach { answer ->
                val response = decode(body(noul, choice, """"q3":$answer"""))
                assertEquals(RatingResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(anger), answer)
                assertTrue(response.answer(team) is ClassificationResult.Selected, answer)
            }
        }
    }

    @Nested
    inner class Mismatches {

        @Test
        fun `wrong kinds, unknown types and missing answers fail only their question`() {
            val response = decode(
                body(""""q1":{"type":"score","score":0}""", """"q2":{"type":"mystery"}"""),
            )
            assertEquals(PropositionResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(urgent))
            assertEquals(ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(team))
            assertEquals(RatingResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(anger))
        }

        @Test
        fun `an unexpected key is ignored and siblings are kept`() {
            val response = decode(body(noul, choice, score, """"q9":{"type":"noul","noul":0.9}"""))
            assertTrue(response.answer(urgent) is PropositionResult.Answered)
            assertTrue(response.answer(anger) is RatingResult.Answered)
        }
    }

    @Nested
    inner class Models {

        @Test
        fun `model names come from the models listing`() {
            val json = """{"models":[{"name":"jev-latest","description":"Jev","release_date":"2026-09-01"},{"name":"jev-1"}]}"""
            assertEquals(listOf("jev-latest", "jev-1"), SystemOneCodec.modelNames(json.toByteArray()))
        }

        @Test
        fun `an OpenAI style listing, as Ollama serves, is read by id`() {
            val json = """{"object":"list","data":[{"id":"clef-flash:latest","object":"model"},{"id":"qwen3:30b"}]}"""
            assertEquals(listOf("clef-flash:latest", "qwen3:30b"), SystemOneCodec.modelNames(json.toByteArray()))
        }

        @Test
        fun `an unusable listing is a format failure`() {
            listOf(
                "{}",
                """{"models":{}}""",
                """{"models":[{"name":" "}]}""",
                """{"models":[{}]}""",
                """{"data":{}}""",
                """{"data":[{"name":"x"}]}""",
            ).forEach { json ->
                assertThrows<SystemOneFormatException>(json) { SystemOneCodec.modelNames(json.toByteArray()) }
            }
        }
    }
}
