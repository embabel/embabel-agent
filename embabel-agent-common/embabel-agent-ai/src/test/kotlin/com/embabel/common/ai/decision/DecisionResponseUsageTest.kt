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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import tools.jackson.databind.json.JsonMapper

class DecisionResponseUsageTest {

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val discovered: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    private val jev = ModelProvenance("jev-latest", "typesafe")

    private val urgent: PropositionQuestionSpec = Questions.named("is_urgent").proposition("Does this convey urgency?").build()

    private val department: ChoiceQuestionSpec = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments")
        .option("technical", "Bugs")
        .build()

    private val frustration: RatingQuestionSpec = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Angry")
        .build()

    private val triage = DecisionSpec.of(urgent, department, frustration)

    private val usage = DecisionUsage(inputTokens = 120, outputTokens = 30, totalTokens = 150)

    private fun plainBuilder(): DecisionResponse.Builder = DecisionResponse.builder(triage)
        .answer(urgent, PropositionResult.Answered(true, jev, 0.9))
        .answer(department, ClassificationResult.Selected("billing", jev))
        .answer(frustration, RatingResult.Answered(jev, selectedLevelId = "Calm"))

    private fun enriched(): DecisionResponse = DecisionResponse.builder(triage)
        .answer(urgent, PropositionResult.Answered(true, jev, 0.9))
        .answer(department, ClassificationResult.Inconclusive(jev))
        .refused(department)
        .answer(frustration, RatingResult.Inconclusive(jev))
        .refused(frustration)
        .usage(usage)
        .build()

    @Nested
    inner class Builder {

        @Test
        fun `usage set on the builder is on the built response`() {
            val response = plainBuilder().usage(usage).build()
            assertEquals(usage, response.usage)
            assertTrue(response.refusedQuestions.isEmpty())
        }

        @Test
        fun `a response built without usage has none`() {
            assertNull(plainBuilder().build().usage)
        }

        @Test
        fun `refused questions are listed in spec order whatever order they were marked in`() {
            val response = DecisionResponse.builder(triage)
                .refused(frustration)
                .answer(frustration, RatingResult.Inconclusive(jev))
                .answer(urgent, PropositionResult.Inconclusive(jev))
                .refused(urgent)
                .answer(department, ClassificationResult.Selected("billing", jev))
                .build()
            assertEquals(listOf("is_urgent", "frustration"), response.refusedQuestions.toList())
            assertTrue(response.wasRefused(urgent))
            assertFalse(response.wasRefused(department))
        }

        @Test
        fun `refusing a question that has an ordinary answer fails at build`() {
            val builder = plainBuilder().refused(department)
            assertThrows(IllegalArgumentException::class.java) { builder.build() }
        }

        @Test
        fun `refusing a question with a failure outcome fails at build`() {
            val builder = DecisionResponse.builder(triage)
                .answer(urgent, PropositionResult.Failure(FailureReason.INVALID_RESPONSE))
                .refused(urgent)
                .answer(department, ClassificationResult.Selected("billing", jev))
                .answer(frustration, RatingResult.Answered(jev, selectedLevelId = "Calm"))
            assertThrows(IllegalArgumentException::class.java) { builder.build() }
        }

        @Test
        fun `refusing the same question twice is rejected like a second answer`() {
            val builder = DecisionResponse.builder(triage).refused(urgent)
            assertThrows(IllegalArgumentException::class.java) { builder.refused(urgent) }
        }

        @Test
        fun `refusing a question the spec does not hold is rejected`() {
            val stranger = Questions.named("stranger").proposition("Is it?").build()
            assertThrows(IllegalArgumentException::class.java) { DecisionResponse.builder(triage).refused(stranger) }
        }

        @Test
        fun `an ordinary inconclusive answer is not a refusal`() {
            val response = DecisionResponse.builder(triage)
                .answer(urgent, PropositionResult.Inconclusive(jev))
                .answer(department, ClassificationResult.Inconclusive(jev))
                .answer(frustration, RatingResult.Inconclusive(jev))
                .build()
            assertFalse(response.wasRefused(urgent))
            assertFalse(response.wasRefused(department))
            assertFalse(response.wasRefused(frustration))
        }
    }

    @Nested
    inner class WasRefused {

        @Test
        fun `wasRefused runs the same name and kind check as the typed answer lookup`() {
            val response = enriched()
            val wrongKind = Questions.named("department").proposition("Is it billing?").build()
            val missing = Questions.named("missing").proposition("Is it?").build()
            assertThrows(IllegalArgumentException::class.java) { response.wasRefused(wrongKind) }
            assertThrows(IllegalArgumentException::class.java) { response.wasRefused(missing) }
        }

        @Test
        fun `a refused question still yields the inconclusive outcome of its kind`() {
            val response = enriched()
            assertTrue(response.wasRefused(department))
            assertEquals(ClassificationResult.Inconclusive(jev), response.answer(department))
            assertEquals(RatingResult.Inconclusive(jev), response.answer(frustration))
        }
    }

    @Nested
    inner class Assembler {

        private fun assembler() = DecisionResponseAssembler.forSpec(triage, "test-service")

        @Test
        fun `a refusal records the inconclusive outcome of the question's kind and marks it refused`() {
            val response = assembler()
                .proposition("is_urgent", PropositionResult.Answered(false, jev))
                .refused("department", jev)
                .refused("frustration", jev)
                .usage(usage)
                .build()
            assertEquals(ClassificationResult.Inconclusive(jev), response.answer(department))
            assertEquals(RatingResult.Inconclusive(jev), response.answer(frustration))
            assertTrue(response.wasRefused(department))
            assertTrue(response.wasRefused(frustration))
            assertFalse(response.wasRefused(urgent))
            assertEquals(usage, response.usage)
            assertNull(response.requestFailure)
        }

        @Test
        fun `an inconclusive answer reported through the kind method is not a refusal`() {
            val response = assembler()
                .proposition("is_urgent", PropositionResult.Inconclusive(jev))
                .choice("department", ClassificationResult.Inconclusive(jev))
                .rating("frustration", RatingResult.Inconclusive(jev))
                .build()
            assertTrue(response.refusedQuestions.isEmpty())
            assertFalse(response.wasRefused(urgent))
        }

        @Test
        fun `a refusal and an answer under one name fail the request like a duplicate answer`() {
            val response = assembler()
                .proposition("is_urgent", PropositionResult.Answered(true, jev))
                .refused("is_urgent", jev)
                .choice("department", ClassificationResult.Selected("billing", jev))
                .rating("frustration", RatingResult.Inconclusive(jev))
                .build()
            assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
            assertTrue(response.refusedQuestions.isEmpty())
        }

        @Test
        fun `two refusals of one name fail the request like a duplicate answer`() {
            val response = assembler()
                .refused("is_urgent", jev)
                .refused("is_urgent", jev)
                .choice("department", ClassificationResult.Selected("billing", jev))
                .rating("frustration", RatingResult.Inconclusive(jev))
                .usage(usage)
                .build()
            assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
            assertTrue(response.refusedQuestions.isEmpty())
            assertEquals(usage, response.usage)
        }

        @Test
        fun `an unsafe envelope fails every question and keeps the recorded usage`() {
            val response = assembler().refused("is_urgent", jev).usage(usage).unsafe().build()
            assertEquals(FailureReason.INVALID_RESPONSE, response.requestFailure)
            assertTrue(response.refusedQuestions.isEmpty())
            assertEquals(usage, response.usage)
        }

        @Test
        fun `a refusal for a name the spec does not hold is ignored and its siblings are kept`() {
            val response = assembler()
                .refused("stranger", jev)
                .proposition("is_urgent", PropositionResult.Answered(true, jev))
                .choice("department", ClassificationResult.Selected("billing", jev))
                .rating("frustration", RatingResult.Inconclusive(jev))
                .build()
            assertNull(response.requestFailure)
            assertTrue(response.refusedQuestions.isEmpty())
        }

        @Test
        fun `usage and refusal cannot be recorded after build`() {
            val assembler = assembler().refused("is_urgent", jev).refused("department", jev).refused("frustration", jev)
            assembler.build()
            assertThrows(IllegalStateException::class.java) { assembler.usage(usage) }
            assertThrows(IllegalStateException::class.java) { assembler.refused("is_urgent", jev) }
        }
    }

    @Nested
    inner class Equality {

        @Test
        fun `two responses differing only in usage are not equal`() {
            val with = plainBuilder().usage(usage).build()
            val without = plainBuilder().build()
            val other = plainBuilder().usage(DecisionUsage(inputTokens = 1)).build()
            assertNotEquals(with, without)
            assertNotEquals(with, other)
            assertEquals(with, plainBuilder().usage(usage).build())
            assertEquals(with.hashCode(), plainBuilder().usage(usage).build().hashCode())
        }

        @Test
        fun `two responses differing only in refusals are not equal`() {
            fun inconclusive() = DecisionResponse.builder(triage)
                .answer(urgent, PropositionResult.Inconclusive(jev))
                .answer(department, ClassificationResult.Inconclusive(jev))
                .answer(frustration, RatingResult.Inconclusive(jev))
            assertNotEquals(inconclusive().build(), inconclusive().refused(urgent).build())
            assertEquals(inconclusive().refused(urgent).build(), inconclusive().refused(urgent).build())
            assertEquals(
                inconclusive().refused(urgent).build().hashCode(),
                inconclusive().refused(urgent).build().hashCode(),
            )
        }
    }

    @Nested
    inner class Json {

        @Test
        fun `a response without usage or refusals writes only requestFailure and answers`() {
            for (m in listOf(mapper, discovered)) {
                val json = m.readTree(m.writeValueAsString(plainBuilder().build()))
                assertEquals(listOf("answers"), json.propertyNames().toList())
                val failed = m.readTree(m.writeValueAsString(DecisionResponse.failed(triage, FailureReason.UNAVAILABLE)))
                assertEquals(listOf("requestFailure", "answers"), failed.propertyNames().toList())
            }
        }

        @Test
        fun `a legacy document reads with no usage and no refusals`() {
            val legacy = mapper.writeValueAsString(plainBuilder().build())
            for (m in listOf(mapper, discovered)) {
                val read = m.readValue(legacy, DecisionResponse::class.java)
                assertNull(read.usage)
                assertTrue(read.refusedQuestions.isEmpty())
                assertEquals(plainBuilder().build(), read)
            }
        }

        @Test
        fun `an enriched response writes usage and refused and round-trips through both mappers`() {
            val response = enriched()
            for (m in listOf(mapper, discovered)) {
                val json = m.writeValueAsString(response)
                val tree = m.readTree(json)
                assertEquals(listOf("answers", "usage", "refused"), tree.propertyNames().toList())
                val refused = tree.get("refused")
                assertEquals(listOf("department", "frustration"), (0 until refused.size()).map { refused.get(it).asString() })
                assertEquals(150, tree.get("usage").get("totalTokens").asInt())
                assertFalse(tree.get("usage").has("reasoningTokens"))
                val read = m.readValue(json, DecisionResponse::class.java)
                assertEquals(response, read)
                assertTrue(read.wasRefused(department))
                assertEquals(usage, read.usage)
            }
        }

        @Test
        fun `explicit null usage and an empty refused list read like absent ones`() {
            val legacy = mapper.writeValueAsString(plainBuilder().build())
            val json = legacy.dropLast(1) + ""","usage":null,"refused":[]}"""
            assertEquals(plainBuilder().build(), mapper.readValue(json, DecisionResponse::class.java))
        }

        @Test
        fun `unknown members are still rejected`() {
            val legacy = mapper.writeValueAsString(plainBuilder().build())
            val json = legacy.dropLast(1) + ""","refusals":["is_urgent"]}"""
            for (m in listOf(mapper, discovered)) {
                assertThrows(DatabindException::class.java) { m.readValue(json, DecisionResponse::class.java) }
            }
        }

        @Test
        fun `a refused name that is not an inconclusive answer is rejected on read`() {
            val legacy = mapper.writeValueAsString(plainBuilder().build())
            for (names in listOf("""["is_urgent"]""", """["nobody"]""", """["is_urgent","is_urgent"]""")) {
                val json = legacy.dropLast(1) + ""","refused":$names}"""
                assertThrows(DatabindException::class.java) { mapper.readValue(json, DecisionResponse::class.java) }
            }
        }

        @Test
        fun `a refused name repeated in JSON is rejected`() {
            val json = mapper.writeValueAsString(enriched())
                .replace(""""refused":["department","frustration"]""", """"refused":["department","department"]""")
            assertThrows(DatabindException::class.java) { mapper.readValue(json, DecisionResponse::class.java) }
        }
    }
}
