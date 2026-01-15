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

import com.embabel.agent.api.common.AgentImage
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.PromptExecutionDelegate
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Predicate

class DelegatingPromptRunnerTest {

    private val mockDelegate = mockk<PromptExecutionDelegate>()

    private fun createPromptRunner(): DelegatingPromptRunner {
        return DelegatingPromptRunner(
            delegate = mockDelegate,
        )
    }

    @Nested
    inner class PropertyDelegationTest {

        @Test
        fun `should delegate toolObjects property`() {
            val toolObjects = listOf(ToolObject.from(mockk()))
            every { mockDelegate.toolObjects } returns toolObjects

            val runner = createPromptRunner()

            assertEquals(toolObjects, runner.toolObjects)
            verify { mockDelegate.toolObjects }
        }

        @Test
        fun `should delegate messages property`() {
            val messages = listOf(UserMessage("test"))
            every { mockDelegate.messages } returns messages

            val runner = createPromptRunner()

            assertEquals(messages, runner.messages)
            verify { mockDelegate.messages }
        }

        @Test
        fun `should delegate images property`() {
            val images = listOf<AgentImage>()
            every { mockDelegate.images } returns images

            val runner = createPromptRunner()

            assertEquals(images, runner.images)
            verify { mockDelegate.images }
        }

        @Test
        fun `should delegate llm property`() {
            val llm = LlmOptions.withModel("test-model")
            every { mockDelegate.llm } returns llm

            val runner = createPromptRunner()

            assertEquals(llm, runner.llm)
            verify { mockDelegate.llm }
        }

        @Test
        fun `should delegate generateExamples property`() {
            every { mockDelegate.generateExamples } returns true

            val runner = createPromptRunner()

            assertEquals(true, runner.generateExamples)
            verify { mockDelegate.generateExamples }
        }

        @Test
        fun `should delegate propertyFilter property`() {
            val filter = Predicate<String> { true }
            every { mockDelegate.propertyFilter } returns filter

            val runner = createPromptRunner()

            assertEquals(filter, runner.propertyFilter)
            verify { mockDelegate.propertyFilter }
        }

        @Test
        fun `should delegate validation property`() {
            every { mockDelegate.validation } returns false

            val runner = createPromptRunner()

            assertEquals(false, runner.validation)
            verify { mockDelegate.validation }
        }
    }

    @Nested
    inner class ConfigurationMethodsTest {

        @Test
        fun `withInteractionId should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val interactionId = InteractionId("test-id")

            every { mockDelegate.withInteractionId(interactionId) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withInteractionId(interactionId)

            verify { mockDelegate.withInteractionId(interactionId) }
            assertTrue(result is DelegatingPromptRunner)
            assertEquals(updatedDelegate, (result as DelegatingPromptRunner).delegate)
        }

        @Test
        fun `withLlm should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val llm = LlmOptions.withModel("test-model")

            every { mockDelegate.withLlm(llm) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withLlm(llm)

            verify { mockDelegate.withLlm(llm) }
            assertTrue(result is DelegatingPromptRunner)
            assertEquals(updatedDelegate, (result as DelegatingPromptRunner).delegate)
        }

        @Test
        fun `withMessages should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val messages = listOf(UserMessage("test"))

            every { mockDelegate.withMessages(messages) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withMessages(messages)

            verify { mockDelegate.withMessages(messages) }
            assertTrue(result is DelegatingPromptRunner)
            assertEquals(updatedDelegate, (result as DelegatingPromptRunner).delegate)
        }

        @Test
        fun `withImages should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val images = listOf<AgentImage>()

            every { mockDelegate.withImages(images) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withImages(images)

            verify { mockDelegate.withImages(images) }
            assertTrue(result is DelegatingPromptRunner)
        }

        @Test
        fun `withToolGroup string should delegate with ToolGroupRequirement`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val groupRole = "test-group"

            every { mockDelegate.withToolGroup(any<ToolGroupRequirement>()) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withToolGroup(groupRole)

            verify { mockDelegate.withToolGroup(match<ToolGroupRequirement> { it.role == groupRole }) }
            assertTrue(result is DelegatingPromptRunner)
        }

        @Test
        fun `withToolGroup ToolGroup should delegate`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val toolGroup = mockk<ToolGroup>()

            every { mockDelegate.withToolGroup(toolGroup) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withToolGroup(toolGroup)

            verify { mockDelegate.withToolGroup(toolGroup) }
            assertTrue(result is DelegatingPromptRunner)
        }

        @Test
        fun `withToolObject should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val toolObject = ToolObject.from(mockk())

            every { mockDelegate.withToolObject(toolObject) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withToolObject(toolObject)

            verify { mockDelegate.withToolObject(toolObject) }
            assertTrue(result is DelegatingPromptRunner)
        }

        @Test
        fun `withTool should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val tool = mockk<Tool>()

            every { mockDelegate.withTool(tool) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withTool(tool)

            verify { mockDelegate.withTool(tool) }
            assertTrue(result is DelegatingPromptRunner)
        }

        @Test
        fun `withPromptContributors should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val contributors = listOf(PromptContributor.fixed("test"))

            every { mockDelegate.withPromptContributors(contributors) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withPromptContributors(contributors)

            verify { mockDelegate.withPromptContributors(contributors) }
            assertTrue(result is DelegatingPromptRunner)
        }

        @Test
        fun `withGenerateExamples should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()

            every { mockDelegate.withGenerateExamples(true) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withGenerateExamples(true)

            verify { mockDelegate.withGenerateExamples(true) }
            assertTrue(result is DelegatingPromptRunner)
        }
    }

    @Nested
    inner class ExecutionMethodsTest {

        @Test
        fun `createObject should delegate to delegate`() {
            val messages = listOf(UserMessage("test"))
            val expectedResult = "result"

            every { mockDelegate.createObject(messages, String::class.java) } returns expectedResult

            val runner = createPromptRunner()
            val result = runner.createObject(messages, String::class.java)

            verify { mockDelegate.createObject(messages, String::class.java) }
            assertEquals(expectedResult, result)
        }

        @Test
        fun `createObjectIfPossible should delegate to delegate`() {
            val messages = listOf(UserMessage("test"))
            val expectedResult = "result"

            every { mockDelegate.createObjectIfPossible(messages, String::class.java) } returns expectedResult

            val runner = createPromptRunner()
            val result = runner.createObjectIfPossible(messages, String::class.java)

            verify { mockDelegate.createObjectIfPossible(messages, String::class.java) }
            assertEquals(expectedResult, result)
        }

        @Test
        fun `respond should delegate to delegate`() {
            val messages = listOf(UserMessage("test"))
            val expectedResponse = AssistantMessage("response")

            every { mockDelegate.respond(messages) } returns expectedResponse

            val runner = createPromptRunner()
            val result = runner.respond(messages)

            verify { mockDelegate.respond(messages) }
            assertEquals(expectedResponse, result)
        }

        @Test
        fun `evaluateCondition should delegate to delegate`() {
            val condition = "test condition"
            val context = "test context"

            every { mockDelegate.evaluateCondition(condition, context, any()) } returns true

            val runner = createPromptRunner()
            val result = runner.evaluateCondition(condition, context)

            verify { mockDelegate.evaluateCondition(condition, context, any()) }
            assertEquals(true, result)
        }
    }
}
