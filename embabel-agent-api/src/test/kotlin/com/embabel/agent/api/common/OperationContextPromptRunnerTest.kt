/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common

import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.core.Operation
import com.embabel.agent.experimental.primitive.Determination
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class OperationContextPromptRunnerTest {

    @Test
    @Disabled("test not implemented yet")
    fun `test contextual prompt contributors`() {
    }

    @Test
    @Disabled("test not implemented yet")
    fun `test withPromptElements`() {
    }

    @Test
    fun `test evaluateCondition`() {
        val mockOperationContext = mockk<OperationContext>()
        every { mockOperationContext.processContext } returns mockk()
        every { mockOperationContext.processContext.agentProcess } returns mockk()
        every {
            mockOperationContext.processContext.createObject(
                any(),
                any(),
                Determination::class.java,
                any(),
                null
            )
        } answers {
            val messages = firstArg<List<Message>>()
            val prompt = (messages[0] as UserMessage).content
            assertTrue(prompt.contains("Evaluate this condition"), "Prompt didn't contain evaluate: $prompt")
            Determination(
                result = true,
                confidence = 0.8,
                explanation = "Mocked explanation"
            )
        }
        every { mockOperationContext.operation } returns mockk<Operation>(relaxed = true)

        val runner = OperationContextPromptRunner(
            context = mockOperationContext,
            llm = LlmOptions(),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = false,
        )

        val result = runner.evaluateCondition("condition", "context", 0.5)
        assertTrue(result)
    }

}
