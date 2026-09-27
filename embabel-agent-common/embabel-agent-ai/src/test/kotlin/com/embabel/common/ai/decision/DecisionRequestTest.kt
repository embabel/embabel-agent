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
    inner class Options {

        @Test
        fun `defaults allow native and single question`() {
            assertEquals(setOf(ExecutionMode.NATIVE, ExecutionMode.SINGLE_QUESTION), DecisionOptions.defaults().executionModes)
        }

        @Test
        fun `nativeOnly allows only native`() {
            assertEquals(setOf(ExecutionMode.NATIVE), DecisionOptions.nativeOnly().executionModes)
        }

        @Test
        fun `allowingSequential allows every mode`() {
            assertEquals(ExecutionMode.entries.toSet(), DecisionOptions.allowingSequential().executionModes)
        }

        @Test
        fun `of holds the given modes`() {
            assertEquals(setOf(ExecutionMode.SEQUENTIAL), DecisionOptions.of(setOf(ExecutionMode.SEQUENTIAL)).executionModes)
        }

        @Test
        fun `of rejects an empty set`() {
            assertRejected("At least one execution mode") { DecisionOptions.of(emptySet()) }
        }

        @Test
        fun `executionModes cannot be modified`() {
            @Suppress("UNCHECKED_CAST")
            val modes = DecisionOptions.defaults().executionModes as MutableSet<ExecutionMode>
            assertThrows(UnsupportedOperationException::class.java) { modes.add(ExecutionMode.SEQUENTIAL) }
        }

        @Test
        fun `changing the set given to of does not change the options`() {
            val modes = mutableSetOf(ExecutionMode.NATIVE)
            val options = DecisionOptions.of(modes)
            modes += ExecutionMode.SEQUENTIAL
            assertEquals(setOf(ExecutionMode.NATIVE), options.executionModes)
        }

        @Test
        fun `options with the same modes are equal`() {
            assertEquals(DecisionOptions.of(setOf(ExecutionMode.NATIVE)), DecisionOptions.of(setOf(ExecutionMode.NATIVE)))
            assertEquals(
                DecisionOptions.of(setOf(ExecutionMode.NATIVE)).hashCode(),
                DecisionOptions.of(setOf(ExecutionMode.NATIVE)).hashCode(),
            )
        }

        @Test
        fun `toString shows the modes`() {
            assertTrue(DecisionOptions.nativeOnly().toString().contains("NATIVE"))
        }
    }

    @Nested
    inner class Capabilities {

        @Test
        fun `limits default to null`() {
            val capabilities = DecisionCapabilities(setOf(QuestionKind.PROPOSITION), setOf(ExecutionMode.NATIVE))
            assertNull(capabilities.maxQuestions)
            assertNull(capabilities.maxInputCharacters)
        }

        @Test
        fun `a null limit means unknown, not zero`() {
            val capabilities = DecisionCapabilities(
                setOf(QuestionKind.PROPOSITION),
                setOf(ExecutionMode.NATIVE),
                maxQuestions = 8,
                maxInputCharacters = 4000,
            )
            assertEquals(8, capabilities.maxQuestions)
            assertEquals(4000, capabilities.maxInputCharacters)
        }

        @Test
        fun `empty question kinds are rejected`() {
            assertRejected("question kind") { DecisionCapabilities(emptySet(), setOf(ExecutionMode.NATIVE)) }
        }

        @Test
        fun `empty execution modes are rejected`() {
            assertRejected("execution mode") { DecisionCapabilities(setOf(QuestionKind.PROPOSITION), emptySet()) }
        }

        @Test
        fun `a maxQuestions below 1 is rejected`() {
            assertRejected("maxQuestions") {
                DecisionCapabilities(setOf(QuestionKind.PROPOSITION), setOf(ExecutionMode.NATIVE), maxQuestions = 0)
            }
        }

        @Test
        fun `a maxInputCharacters below 1 is rejected`() {
            assertRejected("maxInputCharacters") {
                DecisionCapabilities(setOf(QuestionKind.PROPOSITION), setOf(ExecutionMode.NATIVE), maxInputCharacters = -1)
            }
        }

        @Test
        fun `questionKinds and executionModes cannot be modified`() {
            val capabilities = DecisionCapabilities(setOf(QuestionKind.PROPOSITION), setOf(ExecutionMode.NATIVE))
            @Suppress("UNCHECKED_CAST")
            val kinds = capabilities.questionKinds as MutableSet<QuestionKind>
            assertThrows(UnsupportedOperationException::class.java) { kinds.add(QuestionKind.CHOICE) }
            @Suppress("UNCHECKED_CAST")
            val modes = capabilities.executionModes as MutableSet<ExecutionMode>
            assertThrows(UnsupportedOperationException::class.java) { modes.add(ExecutionMode.SEQUENTIAL) }
        }

        @Test
        fun `changing the sets given to the constructor does not change the capabilities`() {
            val kinds = mutableSetOf(QuestionKind.PROPOSITION)
            val modes = mutableSetOf(ExecutionMode.NATIVE)
            val capabilities = DecisionCapabilities(kinds, modes)
            kinds += QuestionKind.CHOICE
            modes += ExecutionMode.SEQUENTIAL
            assertEquals(setOf(QuestionKind.PROPOSITION), capabilities.questionKinds)
            assertEquals(setOf(ExecutionMode.NATIVE), capabilities.executionModes)
        }

        @Test
        fun `capabilities with equal fields are equal`() {
            fun capabilities() = DecisionCapabilities(
                setOf(QuestionKind.PROPOSITION),
                setOf(ExecutionMode.NATIVE),
                maxQuestions = 8,
            )
            assertEquals(capabilities(), capabilities())
            assertEquals(capabilities().hashCode(), capabilities().hashCode())
        }

        @Test
        fun `capabilities with a different limit are not equal`() {
            fun capabilities(max: Int?) =
                DecisionCapabilities(setOf(QuestionKind.PROPOSITION), setOf(ExecutionMode.NATIVE), maxQuestions = max)
            assertNotEquals(capabilities(8), capabilities(9))
            assertNotEquals(capabilities(8), capabilities(null))
        }

        @Test
        fun `toString shows the kinds, modes and limits`() {
            val shown = DecisionCapabilities(
                setOf(QuestionKind.PROPOSITION),
                setOf(ExecutionMode.NATIVE),
                maxQuestions = 8,
            ).toString()
            assertTrue(shown.contains("PROPOSITION"))
            assertTrue(shown.contains("NATIVE"))
            assertTrue(shown.contains("8"))
        }
    }
}
