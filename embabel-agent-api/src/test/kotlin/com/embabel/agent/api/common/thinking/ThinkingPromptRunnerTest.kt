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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests that thinking support on a [PromptRunner] is decided by its type, [ThinkingPromptRunner].
 */
class ThinkingPromptRunnerTest {

    /**
     * Builds a [FakePromptRunner] whose options turn thinking on. The tests use it to show that
     * turning thinking on in the options does not make `thinking()` work on a runner that is
     * not a [ThinkingPromptRunner].
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
    inner class RunnerThatIsNotAThinkingPromptRunner {

        @Test
        fun `reports no thinking support by default`() {
            val runner: PromptRunner = fakeRunnerWithThinkingEnabled()

            // FakePromptRunner does not implement ThinkingPromptRunner, so it keeps the default answer
            assertFalse(runner.supportsThinking())
        }

        @Test
        fun `that says it supports thinking gets UnsupportedOperationException from thinking`() {
            // This runner answers true from supportsThinking() but does not implement
            // ThinkingPromptRunner, so it has no thinking() of its own.
            // Before ThinkingPromptRunner existed, PromptRunner.thinking() trusted the flag and then
            // failed with IllegalStateException ("Implementation error").
            val runner = object : PromptRunner by fakeRunnerWithThinkingEnabled() {
                override fun supportsThinking(): Boolean = true

                // Delegation would forward thinking() to the fake runner. Calling the
                // PromptRunner interface method keeps the behaviour a runner gets when it does
                // not override thinking().
                override fun thinking(): PromptRunner.Thinking = super<PromptRunner>.thinking()
            }

            // The answer is now the documented one for a runner without thinking operations
            assertThrows<UnsupportedOperationException> {
                runner.thinking()
            }
        }
    }

    @Nested
    inner class PlatformRunner {

        private val delegate = mockk<PromptExecutionDelegate>()

        @Test
        fun `is a ThinkingPromptRunner`() {
            val runner: PromptRunner = DelegatingStreamingPromptRunner(delegate)

            // The runner the platform hands out can be checked with `is`
            assertTrue(runner is ThinkingPromptRunner)
        }

        @Test
        fun `takes supportsThinking from the selected model`() {
            every { delegate.supportsThinking() } returns false
            val runner: PromptRunner = DelegatingStreamingPromptRunner(delegate)

            // Being a ThinkingPromptRunner does not override what the selected model reports
            assertFalse(runner.supportsThinking())
            verify { delegate.supportsThinking() }
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
