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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.experimental.primitive.Determination
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.lang.reflect.Field
import java.util.function.Predicate

class DelegatingStreamingPromptRunnerTest {

    private val mockDelegate = mockk<PromptExecutionDelegate>()

    private fun createPromptRunner(): DelegatingStreamingPromptRunner {
        return DelegatingStreamingPromptRunner(
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
        fun `should delegate fieldFilter property`() {
            val filter = Predicate<Field> { true }
            every { mockDelegate.fieldFilter } returns filter

            val runner = createPromptRunner()

            assertEquals(filter, runner.fieldFilter)
            verify { mockDelegate.fieldFilter }
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
            assertTrue(result is DelegatingStreamingPromptRunner)
            assertEquals(updatedDelegate, (result as DelegatingStreamingPromptRunner).delegate)
        }

        @Test
        fun `withLlm should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val llm = LlmOptions.withModel("test-model")

            every { mockDelegate.withLlm(llm) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withLlm(llm)

            verify { mockDelegate.withLlm(llm) }
            assertTrue(result is DelegatingStreamingPromptRunner)
            assertEquals(updatedDelegate, (result as DelegatingStreamingPromptRunner).delegate)
        }

        @Test
        fun `withMessages should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val messages = listOf(UserMessage("test"))

            every { mockDelegate.withMessages(messages) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withMessages(messages)

            verify { mockDelegate.withMessages(messages) }
            assertTrue(result is DelegatingStreamingPromptRunner)
            assertEquals(updatedDelegate, (result as DelegatingStreamingPromptRunner).delegate)
        }

        @Test
        fun `withImages should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val images = listOf<AgentImage>()

            every { mockDelegate.withImages(images) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withImages(images)

            verify { mockDelegate.withImages(images) }
            assertTrue(result is DelegatingStreamingPromptRunner)
        }

        @Test
        fun `withToolGroup string should delegate with ToolGroupRequirement`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val groupRole = "test-group"

            every { mockDelegate.withToolGroup(any<ToolGroupRequirement>()) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withToolGroup(groupRole)

            verify { mockDelegate.withToolGroup(match<ToolGroupRequirement> { it.role == groupRole }) }
            assertTrue(result is DelegatingStreamingPromptRunner)
        }

        @Test
        fun `withToolGroup ToolGroup should delegate`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val toolGroup = mockk<ToolGroup>()

            every { mockDelegate.withToolGroup(toolGroup) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withToolGroup(toolGroup)

            verify { mockDelegate.withToolGroup(toolGroup) }
            assertTrue(result is DelegatingStreamingPromptRunner)
        }

        @Test
        fun `withToolObject should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val toolObject = ToolObject.from(mockk())

            every { mockDelegate.withToolObject(toolObject) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withToolObject(toolObject)

            verify { mockDelegate.withToolObject(toolObject) }
            assertTrue(result is DelegatingStreamingPromptRunner)
        }

        @Test
        fun `withTool should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val tool = mockk<Tool>()

            every { mockDelegate.withTool(tool) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withTool(tool)

            verify { mockDelegate.withTool(tool) }
            assertTrue(result is DelegatingStreamingPromptRunner)
        }

        @Test
        fun `withPromptContributors should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val contributors = listOf(PromptContributor.fixed("test"))

            every { mockDelegate.withPromptContributors(contributors) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withPromptContributors(contributors)

            verify { mockDelegate.withPromptContributors(contributors) }
            assertTrue(result is DelegatingStreamingPromptRunner)
        }

        @Test
        fun `withGenerateExamples should delegate and wrap result`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()

            every { mockDelegate.withGenerateExamples(true) } returns updatedDelegate

            val runner = createPromptRunner()
            val result = runner.withGenerateExamples(true)

            verify { mockDelegate.withGenerateExamples(true) }
            assertTrue(result is DelegatingStreamingPromptRunner)
        }
    }

    @Nested
    inner class FactoryMethodsTest {

        @Test
        fun `creating should not return null`() {
            val outputClass = String::class.java
            val mockObjectMapper = mockk<ObjectMapper>()
            val mockTemplateRenderer = mockk<TemplateRenderer>()

            every { mockDelegate.objectMapper } returns mockObjectMapper
            every { mockDelegate.templateRenderer } returns mockTemplateRenderer

            val runner = createPromptRunner()
            val result = runner.creating(outputClass)

            assertNotNull(result)
        }

        @Test
        fun `rendering should not return null`() {
            val templateName = "test-template"
            val mockTemplateRenderer = mockk<TemplateRenderer>()
            val mockCompiledTemplate = mockk<com.embabel.common.textio.template.CompiledTemplate>()

            every { mockDelegate.templateRenderer } returns mockTemplateRenderer
            every { mockTemplateRenderer.compileLoadedTemplate(templateName) } returns mockCompiledTemplate

            val runner = createPromptRunner()
            val result = runner.rendering(templateName)


            assertNotNull(result)
        }

        @Test
        fun `streaming should return not return null when streaming is supported`() {
            every { mockDelegate.supportsStreaming() } returns true

            val runner = createPromptRunner()
            val result = runner.streaming()

            verify { mockDelegate.supportsStreaming() }
            assertNotNull(result)
        }

        @Test
        fun `streaming should throw exception when streaming is not supported`() {
            every { mockDelegate.supportsStreaming() } returns false
            every { mockDelegate.llmOperations } returns mockk()

            val runner = createPromptRunner()

            try {
                runner.streaming()
                throw AssertionError("Expected UnsupportedOperationException")
            } catch (_: UnsupportedOperationException) {
            }

            verify { mockDelegate.supportsStreaming() }
        }

        @Test
        fun `thinking should not return null`() {
            val runner = createPromptRunner()
            val result = runner.thinking()

            assertNotNull(result)
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
            val expectedResponse = "response"

            every { mockDelegate.createObject(messages, String::class.java) } returns expectedResponse

            val runner = createPromptRunner()
            val result = runner.respond(messages)

            verify { mockDelegate.createObject(any(), any()) }
            assertEquals(expectedResponse, result.content)
        }

        @Test
        fun `evaluateCondition should delegate to delegate`() {
            val condition = "test condition"
            val context = "test context"
            val determination = Determination(
                result = true,
                confidence = 0.9,
                explanation = "Test explanation"
            )

            every { mockDelegate.createObject(any(), Determination::class.java) } returns determination
            every { mockDelegate.llm } returns null

            val runner = createPromptRunner()
            val result = runner.evaluateCondition(condition, context)

            verify { mockDelegate.createObject(any(), Determination::class.java) }
            assertEquals(true, result)
        }

        @Test
        fun `supportsStreaming should delegate to delegate`() {
            every { mockDelegate.supportsStreaming() } returns true

            val runner = createPromptRunner()
            val result = runner.supportsStreaming()

            verify { mockDelegate.supportsStreaming() }
            assertEquals(true, result)
        }

        @Test
        fun `supportsThinking should delegate to delegate`() {
            every { mockDelegate.supportsThinking() } returns true

            val runner = createPromptRunner()
            val result = runner.supportsThinking()

            verify { mockDelegate.supportsThinking() }
            assertEquals(true, result)
        }
    }
}
