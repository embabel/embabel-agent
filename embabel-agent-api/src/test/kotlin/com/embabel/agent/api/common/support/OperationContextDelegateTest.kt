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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.annotation.support.Wumpus
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OperationContextDelegateTest {

    private fun createDelegateWithDefaults(context: OperationContext): OperationContextDelegate {
        return OperationContextDelegate(
            context = context,
            llm = LlmOptions(),
            toolGroups = emptySet(),
            toolObjects = emptyList(),
            promptContributors = emptyList(),
            contextualPromptContributors = emptyList(),
            generateExamples = false,
        )
    }

    @Nested
    inner class ConfigurationMethods {

        @Test
        fun `withLlm should update llm options`() {
            val llm = LlmOptions.withModel("my-model").withTemperature(1.0)
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withLlm(llm)
            assertEquals(llm, delegate.llm, "LlmOptions not set correctly")
        }

        @Test
        fun `withInteractionId should set interaction id`() {
            val interactionId = InteractionId("test-interaction")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withInteractionId(interactionId)
            assertNotNull(delegate, "Delegate should be created successfully")
        }

        @Test
        fun `withToolObject should add tool object`() {
            val wumpus = Wumpus("test-wumpus")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withToolObject(ToolObject(wumpus))
            assertEquals(1, delegate.toolObjects.size, "Must have one tool object")
            assertEquals(wumpus, delegate.toolObjects[0].objects[0], "Tool object instance not set correctly")
        }

        @Test
        fun `withToolGroup should add tool group`() {
            val toolGroup = ToolGroupRequirement("test-group")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withToolGroup(toolGroup)
            assertEquals(1, delegate.toolGroups.size, "Must have one tool group")
            assertTrue(delegate.toolGroups.contains(toolGroup), "Tool group not added correctly")
        }

        @Test
        fun `withPromptContributors should add prompt contributors`() {
            val contributor = PromptContributor.fixed("Test system prompt")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withPromptContributors(listOf(contributor))
            assertEquals(1, delegate.promptContributors.size, "Must have one prompt contributor")
            assertEquals("Test system prompt", delegate.promptContributors[0].contribution())
        }

        @Test
        fun `withGenerateExamples should set generate examples`() {
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withGenerateExamples(true)
            assertEquals(true, delegate.generateExamples, "generateExamples should be true")
        }

        @Test
        fun `withValidation should set validation flag`() {
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withValidation(false)
            assertEquals(false, delegate.validation, "validation should be false")
        }

        @Test
        fun `withMessages should add messages`() {
            val message = UserMessage("Test message")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withMessages(listOf(message))
            assertEquals(1, delegate.messages.size, "Must have one message")
            assertEquals(message, delegate.messages[0], "Message not added correctly")
        }

        @Test
        fun `multiple withMessages calls should accumulate messages`() {
            val message1 = UserMessage("First message")
            val message2 = UserMessage("Second message")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withMessages(listOf(message1))
                .withMessages(listOf(message2))
            assertEquals(2, delegate.messages.size, "Must have two messages")
            assertEquals(message1, delegate.messages[0], "First message not in correct position")
            assertEquals(message2, delegate.messages[1], "Second message not in correct position")
        }
    }

    @Nested
    inner class ImmutabilityTest {

        @Test
        fun `configuration methods should return new instance`() {
            val delegate1 = createDelegateWithDefaults(mockk<OperationContext>())
            val llm = LlmOptions.withModel("different-model")
            val delegate2 = delegate1.withLlm(llm)

            assertNotSame(delegate1, delegate2, "withLlm should return a new instance")
            assertNotEquals(delegate1.llm, delegate2.llm, "LLM options should differ")
        }

        @Test
        fun `withToolObject should not modify original`() {
            val delegate1 = createDelegateWithDefaults(mockk<OperationContext>())
            val wumpus = Wumpus("test-wumpus")
            val delegate2 = delegate1.withToolObject(ToolObject(wumpus))

            assertEquals(0, delegate1.toolObjects.size, "Original delegate should have no tool objects")
            assertEquals(1, delegate2.toolObjects.size, "New delegate should have one tool object")
        }

        @Test
        fun `withPromptContributors should not modify original`() {
            val delegate1 = createDelegateWithDefaults(mockk<OperationContext>())
            val contributor = PromptContributor.fixed("Test")
            val delegate2 = delegate1.withPromptContributors(listOf(contributor))

            assertEquals(0, delegate1.promptContributors.size, "Original delegate should have no contributors")
            assertEquals(1, delegate2.promptContributors.size, "New delegate should have one contributor")
        }
    }

    @Nested
    inner class PropertyAccessTest {

        @Test
        fun `should expose toolObjects property`() {
            val wumpus = Wumpus("test-wumpus")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withToolObject(ToolObject(wumpus))
            assertNotNull(delegate.toolObjects)
            assertEquals(1, delegate.toolObjects.size)
        }

        @Test
        fun `should expose messages property`() {
            val message = UserMessage("Test")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withMessages(listOf(message))
            assertNotNull(delegate.messages)
            assertEquals(1, delegate.messages.size)
        }

        @Test
        fun `should expose llm property`() {
            val llm = LlmOptions.withModel("test-model").withTemperature(1.0)
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withLlm(llm)
            assertEquals(llm, delegate.llm, "LlmOptions not set correctly")
        }

        @Test
        fun `should expose promptContributors property`() {
            val contributor = PromptContributor.fixed("Test")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withPromptContributors(listOf(contributor))
            assertNotNull(delegate.promptContributors)
            assertEquals(1, delegate.promptContributors.size)
        }

        @Test
        fun `should expose toolGroups property`() {
            val toolGroup = ToolGroupRequirement("test-group")
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withToolGroup(toolGroup)
            assertNotNull(delegate.toolGroups)
            assertEquals(1, delegate.toolGroups.size)
        }

        @Test
        fun `should expose generateExamples property`() {
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withGenerateExamples(true)
            assertEquals(true, delegate.generateExamples)
        }

        @Test
        fun `should expose validation property`() {
            val delegate = createDelegateWithDefaults(mockk<OperationContext>())
                .withValidation(false)
            assertEquals(false, delegate.validation)
        }
    }
}
