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
package com.embabel.agent.api.common.nested.support

import com.embabel.agent.api.common.nested.ObjectCreationExample
import com.embabel.agent.api.common.support.DelegatingObjectCreator
import com.embabel.agent.api.common.support.PromptExecutionDelegate
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.textio.template.CompiledTemplate
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Predicate

class DelegatingObjectCreatorTest {

    private val mockDelegate = mockk<PromptExecutionDelegate>()
    private val objectMapper = jacksonObjectMapper()
    private val mockTemplateRenderer = mockk<com.embabel.common.textio.template.TemplateRenderer>()

    private fun createObjectCreator(): DelegatingObjectCreator<String> {
        every { mockDelegate.objectMapper } returns objectMapper
        every { mockDelegate.templateRenderer } returns mockTemplateRenderer
        return DelegatingObjectCreator(
            delegate = mockDelegate,
            outputClass = String::class.java,
        )
    }

    @Nested
    inner class WithExampleTest {

        @Test
        fun `should delegate to withPromptContributors and withGenerateExamples`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val withGenerateExamplesDelegate = mockk<PromptExecutionDelegate>()

            every { mockDelegate.withGenerateExamples(false) } returns withGenerateExamplesDelegate
            every { withGenerateExamplesDelegate.withPromptContributors(any()) } returns updatedDelegate
            every { updatedDelegate.objectMapper } returns objectMapper
            every { updatedDelegate.templateRenderer } returns mockTemplateRenderer

            val example = ObjectCreationExample("test example", "example value")
            val creator = createObjectCreator()

            val result = creator.withExample(example)

            verify { mockDelegate.withGenerateExamples(false) }
            verify { withGenerateExamplesDelegate.withPromptContributors(any()) }
            verify { updatedDelegate.objectMapper }
            verify { updatedDelegate.templateRenderer }
            assertEquals(updatedDelegate, (result as DelegatingObjectCreator<String>).delegate)
        }

        @Test
        fun `should include example description and JSON value in prompt contributor`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val withGenerateExamplesDelegate = mockk<PromptExecutionDelegate>()
            val promptContributorsSlot = slot<List<PromptContributor>>()

            every { mockDelegate.objectMapper } returns objectMapper
            every { mockDelegate.templateRenderer } returns mockTemplateRenderer
            every { mockDelegate.withGenerateExamples(false) } returns withGenerateExamplesDelegate
            every { withGenerateExamplesDelegate.withPromptContributors(capture(promptContributorsSlot)) } returns updatedDelegate
            every { updatedDelegate.objectMapper } returns objectMapper
            every { updatedDelegate.templateRenderer } returns mockTemplateRenderer

            data class TestObject(val name: String, val value: Int)

            val example = ObjectCreationExample("good example", TestObject("test", 42))

            val creator = DelegatingObjectCreator(
                delegate = mockDelegate,
                outputClass = TestObject::class.java,
            )

            creator.withExample(example)

            verify { mockDelegate.objectMapper }
            verify { mockDelegate.templateRenderer }
            verify { updatedDelegate.objectMapper }
            verify { updatedDelegate.templateRenderer }
            val capturedContributors = promptContributorsSlot.captured
            assertEquals(1, capturedContributors.size)
        }
    }

    @Nested
    inner class WithPropertyFilterTest {

        @Test
        fun `should delegate to delegate withPropertyFilter`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val filter = Predicate<String> { it.startsWith("test") }

            every { mockDelegate.withPropertyFilter(filter) } returns updatedDelegate
            every { updatedDelegate.objectMapper } returns objectMapper
            every { updatedDelegate.templateRenderer } returns mockTemplateRenderer

            val creator = createObjectCreator()
            val result = creator.withPropertyFilter(filter)

            verify { mockDelegate.withPropertyFilter(filter) }
            verify { updatedDelegate.objectMapper }
            verify { updatedDelegate.templateRenderer }
            assertEquals(updatedDelegate, (result as DelegatingObjectCreator<String>).delegate)
        }
    }

    @Nested
    inner class WithValidationTest {

        @Test
        fun `should delegate to delegate withValidation`() {
            val updatedDelegate = mockk<PromptExecutionDelegate>()

            every { mockDelegate.withValidation(false) } returns updatedDelegate
            every { updatedDelegate.objectMapper } returns objectMapper
            every { updatedDelegate.templateRenderer } returns mockTemplateRenderer

            val creator = createObjectCreator()
            val result = creator.withValidation(false)

            verify { mockDelegate.withValidation(false) }
            verify { updatedDelegate.objectMapper }
            verify { updatedDelegate.templateRenderer }
            assertEquals(updatedDelegate, (result as DelegatingObjectCreator<String>).delegate)
        }
    }

    @Nested
    inner class FromMessagesTest {

        @Test
        fun `should delegate to delegate createObject with messages`() {
            val messages = listOf(UserMessage("test prompt"))
            val expectedResult = "test result"

            every { mockDelegate.createObject(messages, String::class.java) } returns expectedResult

            val creator = createObjectCreator()
            val result = creator.fromMessages(messages)

            verify { mockDelegate.createObject(messages, String::class.java) }
            assertEquals(expectedResult, result)
        }
    }

    @Nested
    inner class FromTemplateTest {

        @Test
        fun `should compile template, render it, and delegate to createObject`() {
            val templateName = "test-template"
            val model = mapOf("name" to "World")
            val expectedResult = "test result"
            val messagesSlot = slot<List<Message>>()
            val mockCompiledTemplate = mockk<CompiledTemplate>()
            val renderedText = "Hello World"

            every { mockTemplateRenderer.compileLoadedTemplate(templateName) } returns mockCompiledTemplate
            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(capture(messagesSlot), String::class.java) } returns expectedResult

            val creator = createObjectCreator()
            val result = creator.fromTemplate(templateName, model)

            verify { mockTemplateRenderer.compileLoadedTemplate(templateName) }
            verify { mockCompiledTemplate.render(model) }
            verify { mockDelegate.createObject(any(), String::class.java) }
            assertEquals(expectedResult, result)

            val capturedMessages = messagesSlot.captured
            assertEquals(1, capturedMessages.size)
            assert(capturedMessages[0] is UserMessage)
            assertEquals(renderedText, (capturedMessages[0] as UserMessage).content)
        }
    }
}
