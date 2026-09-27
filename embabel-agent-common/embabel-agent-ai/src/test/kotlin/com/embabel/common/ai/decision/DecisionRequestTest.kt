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
        fun `of with modes holds exactly those modes`() {
            assertEquals(
                setOf(ExecutionMode.NATIVE, ExecutionMode.SEQUENTIAL),
                DecisionOptions.of(ExecutionMode.SEQUENTIAL, ExecutionMode.NATIVE).executionModes,
            )
        }

        @Test
        fun `of with one mode equals the options built from a set`() {
            assertEquals(DecisionOptions.nativeOnly(), DecisionOptions.of(ExecutionMode.NATIVE))
            assertEquals(DecisionOptions.of(setOf(ExecutionMode.SEQUENTIAL)), DecisionOptions.of(ExecutionMode.SEQUENTIAL))
        }

        @Test
        fun `of with a repeated mode holds it once`() {
            assertEquals(
                setOf(ExecutionMode.NATIVE),
                DecisionOptions.of(ExecutionMode.NATIVE, ExecutionMode.NATIVE).executionModes,
            )
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

        @Test
        fun `options with different modes are not equal`() {
            assertNotEquals(DecisionOptions.nativeOnly(), DecisionOptions.defaults())
            assertNotEquals(DecisionOptions.defaults(), DecisionOptions.allowingSequential())
        }

        @Test
        fun `modes iterate in declaration order whatever order they were given in`() {
            val given = linkedSetOf(ExecutionMode.SEQUENTIAL, ExecutionMode.SINGLE_QUESTION, ExecutionMode.NATIVE)
            assertEquals(ExecutionMode.entries, DecisionOptions.of(given).executionModes.toList())
            assertEquals(ExecutionMode.entries, DecisionOptions.allowingSequential().executionModes.toList())
            assertEquals(
                listOf(ExecutionMode.NATIVE, ExecutionMode.SINGLE_QUESTION),
                DecisionOptions.defaults().executionModes.toList(),
            )
        }

        @Test
        fun `toString lists the modes in declaration order`() {
            val given = linkedSetOf(ExecutionMode.SEQUENTIAL, ExecutionMode.SINGLE_QUESTION, ExecutionMode.NATIVE)
            assertEquals(
                "DecisionOptions(executionModes=[NATIVE, SINGLE_QUESTION, SEQUENTIAL])",
                DecisionOptions.of(given).toString(),
            )
        }

        @Test
        fun `has no public constructor`() {
            assertTrue(DecisionOptions::class.java.constructors.none { !it.isSynthetic })
        }
    }

    @Nested
    inner class Capabilities {

        private fun capabilities(
            kinds: Set<QuestionKind> = setOf(QuestionKind.PROPOSITION),
            modes: Set<ExecutionMode> = setOf(ExecutionMode.NATIVE),
        ): DecisionCapabilities = DecisionCapabilities.of(kinds, modes)

        @Test
        fun `of holds the given kinds and modes`() {
            val capabilities = DecisionCapabilities.of(
                setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE),
                setOf(ExecutionMode.NATIVE, ExecutionMode.SINGLE_QUESTION),
            )
            assertEquals(setOf(QuestionKind.PROPOSITION, QuestionKind.CHOICE), capabilities.questionKinds)
            assertEquals(setOf(ExecutionMode.NATIVE, ExecutionMode.SINGLE_QUESTION), capabilities.executionModes)
        }

        @Test
        fun `limits are null until set`() {
            val capabilities = capabilities()
            assertNull(capabilities.maxQuestions)
            assertNull(capabilities.maxInputCharacters)
        }

        @Test
        fun `a present limit is kept as given`() {
            val capabilities = capabilities().withMaxQuestions(8).withMaxInputCharacters(4000)
            assertEquals(8, capabilities.maxQuestions)
            assertEquals(4000, capabilities.maxInputCharacters)
        }

        @Test
        fun `withMaxQuestions returns new capabilities and keeps everything else`() {
            val original = capabilities().withMaxInputCharacters(4000)
            val limited = original.withMaxQuestions(8)
            assertNull(original.maxQuestions)
            assertEquals(8, limited.maxQuestions)
            assertEquals(4000, limited.maxInputCharacters)
            assertEquals(original.questionKinds, limited.questionKinds)
            assertEquals(original.executionModes, limited.executionModes)
        }

        @Test
        fun `withMaxInputCharacters returns new capabilities and keeps everything else`() {
            val original = capabilities().withMaxQuestions(8)
            val limited = original.withMaxInputCharacters(4000)
            assertNull(original.maxInputCharacters)
            assertEquals(4000, limited.maxInputCharacters)
            assertEquals(8, limited.maxQuestions)
            assertEquals(original.questionKinds, limited.questionKinds)
            assertEquals(original.executionModes, limited.executionModes)
        }

        @Test
        fun `a later wither replaces an earlier limit`() {
            assertEquals(3, capabilities().withMaxQuestions(8).withMaxQuestions(3).maxQuestions)
        }

        @Test
        fun `empty question kinds are rejected`() {
            assertRejected("question kind") { DecisionCapabilities.of(emptySet(), setOf(ExecutionMode.NATIVE)) }
        }

        @Test
        fun `empty execution modes are rejected`() {
            assertRejected("execution mode") { DecisionCapabilities.of(setOf(QuestionKind.PROPOSITION), emptySet()) }
        }

        @Test
        fun `a maxQuestions below 1 is rejected`() {
            assertRejected("maxQuestions") { capabilities().withMaxQuestions(0) }
        }

        @Test
        fun `a maxInputCharacters below 1 is rejected`() {
            assertRejected("maxInputCharacters") { capabilities().withMaxInputCharacters(-1) }
        }

        @Test
        fun `a limit of 1 is accepted`() {
            val capabilities = capabilities().withMaxQuestions(1).withMaxInputCharacters(1)
            assertEquals(1, capabilities.maxQuestions)
            assertEquals(1, capabilities.maxInputCharacters)
        }

        @Test
        fun `questionKinds and executionModes cannot be modified`() {
            val capabilities = capabilities().withMaxQuestions(8)
            @Suppress("UNCHECKED_CAST")
            val kinds = capabilities.questionKinds as MutableSet<QuestionKind>
            assertThrows(UnsupportedOperationException::class.java) { kinds.add(QuestionKind.CHOICE) }
            @Suppress("UNCHECKED_CAST")
            val modes = capabilities.executionModes as MutableSet<ExecutionMode>
            assertThrows(UnsupportedOperationException::class.java) { modes.add(ExecutionMode.SEQUENTIAL) }
        }

        @Test
        fun `changing the sets given to of does not change the capabilities`() {
            val kinds = mutableSetOf(QuestionKind.PROPOSITION)
            val modes = mutableSetOf(ExecutionMode.NATIVE)
            val capabilities = DecisionCapabilities.of(kinds, modes)
            kinds += QuestionKind.CHOICE
            modes += ExecutionMode.SEQUENTIAL
            assertEquals(setOf(QuestionKind.PROPOSITION), capabilities.questionKinds)
            assertEquals(setOf(ExecutionMode.NATIVE), capabilities.executionModes)
        }

        @Test
        fun `capabilities with equal fields are equal`() {
            assertEquals(capabilities().withMaxQuestions(8), capabilities().withMaxQuestions(8))
            assertEquals(capabilities().withMaxQuestions(8).hashCode(), capabilities().withMaxQuestions(8).hashCode())
        }

        @Test
        fun `capabilities with a different limit are not equal`() {
            assertNotEquals(capabilities().withMaxQuestions(8), capabilities().withMaxQuestions(9))
            assertNotEquals(capabilities().withMaxQuestions(8), capabilities())
            assertNotEquals(capabilities().withMaxInputCharacters(8), capabilities().withMaxQuestions(8))
        }

        @Test
        fun `capabilities with different execution modes are not equal`() {
            assertNotEquals(
                capabilities(modes = setOf(ExecutionMode.NATIVE)),
                capabilities(modes = setOf(ExecutionMode.NATIVE, ExecutionMode.SEQUENTIAL)),
            )
        }

        @Test
        fun `capabilities with different question kinds are not equal`() {
            assertNotEquals(
                capabilities(kinds = setOf(QuestionKind.PROPOSITION)),
                capabilities(kinds = setOf(QuestionKind.CHOICE)),
            )
        }

        @Test
        fun `kinds and modes iterate in declaration order whatever order they were given in`() {
            val capabilities = capabilities(
                kinds = linkedSetOf(QuestionKind.RATING, QuestionKind.CHOICE, QuestionKind.PROPOSITION),
                modes = linkedSetOf(ExecutionMode.SEQUENTIAL, ExecutionMode.SINGLE_QUESTION, ExecutionMode.NATIVE),
            ).withMaxQuestions(8)
            assertEquals(QuestionKind.entries, capabilities.questionKinds.toList())
            assertEquals(ExecutionMode.entries, capabilities.executionModes.toList())
        }

        @Test
        fun `toString lists kinds and modes in declaration order with the limits`() {
            val shown = capabilities(
                kinds = linkedSetOf(QuestionKind.RATING, QuestionKind.CHOICE, QuestionKind.PROPOSITION),
                modes = linkedSetOf(ExecutionMode.SEQUENTIAL, ExecutionMode.SINGLE_QUESTION, ExecutionMode.NATIVE),
            ).withMaxQuestions(8).toString()
            assertEquals(
                "DecisionCapabilities(questionKinds=[PROPOSITION, CHOICE, RATING], " +
                    "executionModes=[NATIVE, SINGLE_QUESTION, SEQUENTIAL], maxQuestions=8, maxInputCharacters=null)",
                shown,
            )
        }

        @Test
        fun `has no public constructor`() {
            assertTrue(DecisionCapabilities::class.java.constructors.none { !it.isSynthetic })
        }
    }
}
