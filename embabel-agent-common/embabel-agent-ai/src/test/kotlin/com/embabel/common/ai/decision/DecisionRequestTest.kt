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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class DecisionRequestTest {

    private fun urgent(): PropositionQuestionSpec =
        Questions.named("is_urgent").proposition("Does this convey urgency?").build()

    private fun department(): ChoiceQuestionSpec = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .build()

    private fun assertRejected(vararg fragments: String, block: () -> Unit): IllegalArgumentException {
        val error = assertThrows(IllegalArgumentException::class.java, Executable(block))
        for (fragment in fragments) {
            assertTrue(error.message!!.contains(fragment)) { "Expected '$fragment' in: ${error.message}" }
        }
        return error
    }

    @Nested
    inner class Request {

        @Test
        fun `of with a spec holds the given input and spec`() {
            val spec = DecisionSpec.of(urgent())
            val request = DecisionRequest.of("A customer email.", spec)
            assertEquals("A customer email.", request.input)
            assertEquals(spec, request.spec)
        }

        @Test
        fun `input may be empty`() {
            val request = DecisionRequest.of("", DecisionSpec.of(urgent()))
            assertEquals("", request.input)
        }

        @Test
        fun `of with questions builds a spec from them in order`() {
            val request = DecisionRequest.of("text", urgent(), department())
            assertEquals(DecisionSpec.of(urgent(), department()), request.spec)
        }

        @Test
        fun `of with questions rejects an empty question list`() {
            assertRejected("at least one question") { DecisionRequest.of("text") }
        }

        @Test
        fun `of with questions rejects a repeated name`() {
            assertRejected("'is_urgent'") { DecisionRequest.of("text", urgent(), urgent()) }
        }

        @Test
        fun `requests with equal input and spec are equal`() {
            val spec = DecisionSpec.of(urgent())
            assertEquals(DecisionRequest.of("text", spec), DecisionRequest.of("text", spec))
            assertEquals(
                DecisionRequest.of("text", spec).hashCode(),
                DecisionRequest.of("text", spec).hashCode(),
            )
        }

        @Test
        fun `requests with different input are not equal`() {
            val spec = DecisionSpec.of(urgent())
            assertNotEquals(DecisionRequest.of("a", spec), DecisionRequest.of("b", spec))
        }

        @Test
        fun `requests with different specs are not equal`() {
            assertNotEquals(
                DecisionRequest.of("text", urgent()),
                DecisionRequest.of("text", department()),
            )
        }

        @Test
        fun `toString omits the input`() {
            val request = DecisionRequest.of("a secret customer email", DecisionSpec.of(urgent()))
            val shown = request.toString()
            assertFalse(shown.contains("secret"))
            assertTrue(shown.contains("DecisionSpec"))
        }
    }

    @Nested
    inner class Capabilities {

        private fun capabilities(
            kinds: Set<QuestionKind> = setOf(QuestionKind.PROPOSITION),
        ): DecisionCapabilities = DecisionCapabilities.of(kinds)

        @Test
        fun `of holds the given kinds`() {
            val capabilities = DecisionCapabilities.of(setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE))
            assertEquals(setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE), capabilities.questionKinds)
        }

        @Test
        fun `empty question kinds are rejected`() {
            assertRejected("question kind") { DecisionCapabilities.of(emptySet()) }
        }

        @Test
        fun `questionKinds cannot be modified`() {
            val capabilities = capabilities()
            @Suppress("UNCHECKED_CAST")
            val kinds = capabilities.questionKinds as MutableSet<QuestionKind>
            assertThrows(UnsupportedOperationException::class.java) { kinds.add(QuestionKind.CHOICE) }
        }

        @Test
        fun `changing the set given to of does not change the capabilities`() {
            val kinds = mutableSetOf(QuestionKind.PROPOSITION)
            val capabilities = DecisionCapabilities.of(kinds)
            kinds += QuestionKind.CHOICE
            assertEquals(setOf(QuestionKind.PROPOSITION), capabilities.questionKinds)
        }

        @Test
        fun `capabilities with equal fields are equal`() {
            assertEquals(capabilities(), capabilities())
            assertEquals(capabilities().hashCode(), capabilities().hashCode())
        }

        @Test
        fun `capabilities with different question kinds are not equal`() {
            assertNotEquals(
                capabilities(kinds = setOf(QuestionKind.PROPOSITION)),
                capabilities(kinds = setOf(QuestionKind.CHOICE)),
            )
        }

        @Test
        fun `kinds iterate in declaration order whatever order they were given in`() {
            val capabilities = capabilities(
                kinds = linkedSetOf(QuestionKind.RATING, QuestionKind.CHOICE, QuestionKind.PROPOSITION),
            )
            assertEquals(QuestionKind.entries, capabilities.questionKinds.toList())
        }

        @Test
        fun `toString lists kinds in declaration order`() {
            val shown = capabilities(
                kinds = linkedSetOf(QuestionKind.RATING, QuestionKind.CHOICE, QuestionKind.PROPOSITION),
            ).toString()
            assertEquals("DecisionCapabilities(questionKinds=[PROPOSITION, CHOICE, RATING])", shown)
        }

        @Test
        fun `has no public constructor`() {
            assertTrue(DecisionCapabilities::class.java.constructors.none { !it.isSynthetic })
        }
    }
}
