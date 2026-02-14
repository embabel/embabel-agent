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
package com.embabel.agent.spi

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LlmOperationsTest {

    private val agentProcess = mockk<AgentProcess>()
    private val action = mockk<Action>()
    private val interaction = mockk<LlmInteraction>()

    /**
     * Concrete implementation that captures arguments to the abstract methods,
     * allowing us to verify default method delegation behavior.
     */
    private class CapturingLlmOperations : LlmOperations {
        var lastCreateObjectMessages: List<Message>? = null
        var lastCreateObjectOutputClass: Class<*>? = null
        var createObjectReturn: Any? = "generated text"

        var lastDoTransformMessages: List<Message>? = null
        var lastDoTransformOutputClass: Class<*>? = null
        var doTransformReturn: Any? = "transformed"

        @Suppress("UNCHECKED_CAST")
        override fun <O> createObject(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?,
        ): O {
            lastCreateObjectMessages = messages
            lastCreateObjectOutputClass = outputClass
            return createObjectReturn as O
        }

        override fun <O> createObjectIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?,
        ): Result<O> {
            throw UnsupportedOperationException("Not under test")
        }

        @Suppress("UNCHECKED_CAST")
        override fun <O> doTransform(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): O {
            lastDoTransformMessages = messages
            lastDoTransformOutputClass = outputClass
            return doTransformReturn as O
        }
    }

    @Nested
    inner class Generate {

        @Test
        fun `delegates to createObject with UserMessage and String class`() {
            val ops = CapturingLlmOperations()
            ops.createObjectReturn = "hello world"

            val result = ops.generate("say hello", interaction, agentProcess, action)

            assertThat(result).isEqualTo("hello world")
            assertThat(ops.lastCreateObjectMessages).hasSize(1)
            assertThat(ops.lastCreateObjectMessages!![0]).isInstanceOf(UserMessage::class.java)
            assertThat((ops.lastCreateObjectMessages!![0] as UserMessage).content).isEqualTo("say hello")
            assertThat(ops.lastCreateObjectOutputClass).isEqualTo(String::class.java)
        }

        @Test
        fun `passes null action through`() {
            val ops = CapturingLlmOperations()

            ops.generate("prompt", interaction, agentProcess, null)

            assertThat(ops.lastCreateObjectMessages).isNotNull
        }
    }

    @Nested
    inner class DoTransformWithPrompt {

        @Test
        fun `delegates to doTransform with UserMessage list`() {
            val ops = CapturingLlmOperations()
            ops.doTransformReturn = 42

            val result = ops.doTransform("convert this", interaction, Int::class.java, null)

            assertThat(result).isEqualTo(42)
            assertThat(ops.lastDoTransformMessages).hasSize(1)
            assertThat(ops.lastDoTransformMessages!![0]).isInstanceOf(UserMessage::class.java)
            assertThat((ops.lastDoTransformMessages!![0] as UserMessage).content).isEqualTo("convert this")
            assertThat(ops.lastDoTransformOutputClass).isEqualTo(Int::class.java)
        }

        @Test
        fun `passes llmRequestEvent through`() {
            val ops = CapturingLlmOperations()
            val event = mockk<LlmRequestEvent<String>>()
            ops.doTransformReturn = "result"

            val result = ops.doTransform("prompt", interaction, String::class.java, event)

            assertThat(result).isEqualTo("result")
        }
    }
}
