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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.support.DelegatingStreamingPromptRunner
import com.embabel.agent.api.common.support.PromptExecutionDelegate
import com.embabel.agent.test.unit.FakePromptRunner
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.embabel.common.core.thinking.ThinkingResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests that thinking on a [PromptRunner] works when the runner implements `thinking()`, whatever
 * `supportsThinking()` says, and throws [UnsupportedOperationException] when it does not.
 */
class PromptRunnerThinkingTest {

    /**
     * Builds a [FakePromptRunner] whose options turn thinking on. The tests use it to show that
     * turning thinking on in the options does not make `thinking()` work on a runner that does
     * not implement it.
     */
    private fun fakeRunnerWithThinkingEnabled(): FakePromptRunner = FakePromptRunner(
        llm = LlmOptions().withThinking(Thinking.withExtraction()),
        toolGroups = emptySet(),
        toolObjects = emptyList(),
        promptContributors = emptyList(),
        contextualPromptContributors = emptyList(),
        generateExamples = null,
        context = mockk<OperationContext>(),
    )

    @Nested
    inner class RunnerThatDoesNotImplementThinking {

        @Test
        fun `reports no thinking support by default`() {
            val runner: PromptRunner = fakeRunnerWithThinkingEnabled()

            // FakePromptRunner does not implement thinking(), so it keeps the default answer
            assertFalse(runner.supportsThinking())
        }

        @Test
        fun `that says it supports thinking gets UnsupportedOperationException from thinking`() {
            // This runner answers true from supportsThinking() but has no thinking() of its own.
            // In 1.5, PromptRunner.thinking() checked supportsThinking() and the thinking setting
            // in LlmOptions. This runner passes both checks, so the old code threw
            // IllegalStateException.
            val runner = object : PromptRunner by fakeRunnerWithThinkingEnabled() {
                override fun supportsThinking(): Boolean = true

                // Delegation would forward thinking() to the fake runner. Calling the
                // PromptRunner interface method keeps the behaviour a runner gets when it does
                // not override thinking().
                override fun thinking(): PromptRunner.Thinking = super<PromptRunner>.thinking()
            }

            // thinking() throws UnsupportedOperationException, as documented for a runner that does not implement it
            val failure = assertThrows<UnsupportedOperationException> {
                runner.thinking()
            }
            // The message does not send callers to the deprecated supportsThinking()
            val message = failure.message.orEmpty()
            assertFalse(message.contains("supportsThinking"), message)
        }

        @Test
        fun `a custom runner that overrides thinking returns its own operations`() {
            val operations = mockk<PromptRunner.Thinking>()
            val runner: PromptRunner = object : PromptRunner by fakeRunnerWithThinkingEnabled() {
                override fun thinking(): PromptRunner.Thinking = operations
            }

            assertSame(operations, runner.thinking())
        }
    }

    @Nested
    inner class SupportsThinkingOnTheRunner {

        @Test
        fun `is deprecated because thinking does not consult it`() {
            val deprecated = PromptRunner::class.java.getMethod("supportsThinking").getAnnotation(Deprecated::class.java)

            requireNotNull(deprecated) { "PromptRunner.supportsThinking() should be deprecated" }
            assertTrue(deprecated.message.contains("Not a gate"), deprecated.message)
        }
    }

    @Nested
    inner class PlatformRunner {

        private val delegate = mockk<PromptExecutionDelegate>()

        @Test
        fun `takes supportsThinking from the selected model`() {
            every { delegate.supportsThinking() } returns false
            val runner: PromptRunner = DelegatingStreamingPromptRunner(delegate)

            // The runner passes on what the selected model reports
            assertFalse(runner.supportsThinking())
            verify { delegate.supportsThinking() }
        }

        @Test
        fun `runs thinking even when the selected model reports no thinking support`() {
            // Providers such as DeepSeek and Mistral report false, yet their models reason.
            // Extraction is prompt based, so thinking() does not consult supportsThinking().
            every { delegate.supportsThinking() } returns false
            val expected = ThinkingResponse(result = "answer", thinkingBlocks = emptyList())
            every {
                delegate.createObjectWithThinking(any<List<Message>>(), String::class.java)
            } returns expected
            val runner: PromptRunner = DelegatingStreamingPromptRunner(delegate)

            val response = runner.thinking().createObject("question", String::class.java)

            assertEquals(expected, response)
            verify(exactly = 0) { delegate.supportsThinking() }
        }

        @Test
        fun `sends thinking calls to the delegate`() {
            val expected = ThinkingResponse(result = "answer", thinkingBlocks = emptyList())
            every {
                delegate.createObjectWithThinking(any<List<Message>>(), String::class.java)
            } returns expected
            val runner: PromptRunner = DelegatingStreamingPromptRunner(delegate)

            val response = runner.thinking().createObject("question", String::class.java)

            // The response is the one the delegate produced, so the call went through thinking
            assertEquals(expected, response)
            verify { delegate.createObjectWithThinking(any<List<Message>>(), String::class.java) }
        }
    }
}
