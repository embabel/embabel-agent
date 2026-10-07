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
package com.embabel.common.ai.classification

import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import tools.jackson.databind.json.JsonMapper

class ClassificationSpecTest {

    private val provenance = ModelProvenance("model", "provider")

    private fun departments(): ClassificationSpec = ClassificationSpec.builder()
        .asking("Which team should handle this?")
        .category("billing", "Payments, invoicing, refunds")
        .category("technical", "Bugs, outages, integrations")
        .build()

    @Nested
    inner class Spec {

        @Test
        fun `builder makes one choice question with the default name`() {
            val spec = departments()
            assertEquals(ClassificationSpec.QUESTION_NAME, spec.question.name)
            assertEquals("classification", spec.question.name)
            assertEquals(QuestionKind.CHOICE, spec.question.kind)
            assertEquals(listOf(spec.question), spec.questions)
            assertEquals("Which team should handle this?", spec.instructions)
            assertEquals(listOf("billing", "technical"), spec.categories.map { it.id })
            assertEquals(spec.question.options, spec.categories)
        }

        @Test
        fun `a classification spec equals a decision spec with the same single question`() {
            val spec = departments()
            val decision = DecisionSpec.of(spec.question)
            assertEquals(decision, spec)
            assertEquals(spec, decision)
            assertEquals(decision.hashCode(), spec.hashCode())
        }

        @Test
        fun `of keeps the name of the wrapped question`() {
            val question = Questions.named("department").choice("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .build()
            val spec = ClassificationSpec.of(question)
            assertSame(question, spec.question)
            assertEquals("department", spec.question.name)
        }

        @Test
        fun `builder needs instructions and at least one category`() {
            assertThrows(IllegalArgumentException::class.java) {
                ClassificationSpec.builder().category("billing", "Payments").build()
            }
            assertThrows(IllegalArgumentException::class.java) {
                ClassificationSpec.builder().asking("Which team?").build()
            }
            assertThrows(IllegalArgumentException::class.java) {
                ClassificationSpec.builder().asking("Which team?")
                    .category("billing", "first").category("billing", "second").build()
            }
        }

        @Test
        fun `dsl builds the same spec as the builder`() {
            // tag::dsl[]
            val departments = classificationSpec {
                asking("Which team should handle this?")
                category("billing", "Payments, invoicing, refunds")
                category("technical", "Bugs, outages, integrations")
            }
            // end::dsl[]
            assertEquals(departments(), departments)
        }

        @Test
        fun `selected and validate only accept the spec's categories`() {
            val spec = departments()
            val selected = spec.selected("billing", provenance, 0.7)
            assertEquals(ClassificationResult.Selected("billing", provenance, 0.7), selected)
            assertSame(selected, spec.validate(selected))
            assertThrows(IllegalArgumentException::class.java) { spec.selected("sales", provenance) }
            assertThrows(IllegalArgumentException::class.java) {
                spec.validate(ClassificationResult.Selected("sales", provenance))
            }
            val noMatch = ClassificationResult.NoMatch(provenance)
            assertSame(noMatch, spec.validate(noMatch))
        }
    }

    @Nested
    inner class Request {

        @Test
        fun `a classification request is a decision request over its spec`() {
            val spec = departments()
            val request = ClassificationRequest.of("My card was charged twice", spec)
            val decision: DecisionRequest = request
            assertSame(spec, decision.spec)
            assertEquals(DecisionRequest.of("My card was charged twice", DecisionSpec.of(spec.question)), request)
            assertEquals("My card was charged twice", request.input)
            assertEquals(spec.categories, request.categories)
            assertEquals(spec.instructions, request.instructions)
        }

        @Test
        fun `request string leaves out the input and category descriptions`() {
            val request = ClassificationRequest.of("sensitive-input", departments())
            assertFalse(request.toString().contains("sensitive-input"))
            assertFalse(request.toString().contains("Payments"))
        }

        @Test
        fun `a decision response to the request answers the spec's question`() {
            val spec = departments()
            val request = ClassificationRequest.of("My card was charged twice", spec)
            val response = DecisionResponse.builder(request.spec)
                .answer(spec.question, spec.selected("billing", provenance))
                .build()
            val result: ClassificationResult = response.answer(spec.question)
            assertEquals(ClassificationResult.Selected("billing", provenance), result)
        }

        @Test
        fun `classify with input and spec builds the request`() {
            var seen: ClassificationRequest? = null
            val classifier = object : ClassificationService {
                override val name = "model"
                override val provider = "provider"
                override fun classify(request: ClassificationRequest): ClassificationResult {
                    seen = request
                    return ClassificationResult.NoMatch(provenance)
                }
            }
            val spec = departments()
            classifier.classify("My card was charged twice", spec)
            assertEquals(ClassificationRequest.of("My card was charged twice", spec), seen)
        }
    }

    @Nested
    inner class Json {

        private val mapper: JsonMapper = JsonMapper.builder().build()

        @Test
        fun `spec and request write as decision JSON`() {
            val spec = departments()
            assertEquals(
                mapper.writeValueAsString(DecisionSpec.of(spec.question)),
                mapper.writeValueAsString(spec),
            )
            val request = ClassificationRequest.of("text", spec)
            assertEquals(
                mapper.writeValueAsString(DecisionRequest.of("text", DecisionSpec.of(spec.question))),
                mapper.writeValueAsString(request),
            )
        }

        @Test
        fun `spec JSON reads as a decision spec or a classification spec`() {
            val spec = departments()
            val json = mapper.writeValueAsString(spec)
            val decision = mapper.readValue(json, DecisionSpec::class.java)
            assertEquals(DecisionSpec::class.java, decision.javaClass)
            assertEquals(spec, decision)
            val classification = mapper.readValue(json, ClassificationSpec::class.java)
            assertEquals(spec, classification)
            assertEquals(spec.question, classification.question)
        }

        @Test
        fun `request JSON reads as a classification request`() {
            val request = ClassificationRequest.of("text", departments())
            val json = mapper.writeValueAsString(request)
            assertEquals(request, mapper.readValue(json, ClassificationRequest::class.java))
            assertEquals(DecisionRequest::class.java, mapper.readValue(json, DecisionRequest::class.java).javaClass)
        }

        @Test
        fun `reading a classification spec rejects other shapes and unknown members`() {
            val two = DecisionSpec.builder()
                .proposition("urgent") { it.asking("Is it urgent?") }
                .question(departments().question)
                .build()
            val proposition = DecisionSpec.builder().proposition("urgent") { it.asking("Is it urgent?") }.build()
            for (other in listOf(two, proposition)) {
                assertThrows(DatabindException::class.java) {
                    mapper.readValue(mapper.writeValueAsString(other), ClassificationSpec::class.java)
                }
            }
            val extra = mapper.writeValueAsString(departments()).dropLast(1) + ""","extra":1}"""
            val failure = assertThrows(DatabindException::class.java) {
                mapper.readValue(extra, ClassificationSpec::class.java)
            }
            assertTrue(failure.message!!.contains("extra"), failure.message)
        }
    }
}
