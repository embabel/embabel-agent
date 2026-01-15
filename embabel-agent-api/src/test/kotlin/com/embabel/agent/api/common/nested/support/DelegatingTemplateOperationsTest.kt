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

import com.embabel.agent.api.common.PromptExecutionDelegate
import com.embabel.chat.*
import com.embabel.common.textio.template.CompiledTemplate
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DelegatingTemplateOperationsTest {

    private val mockDelegate = mockk<PromptExecutionDelegate>()
    private val mockTemplateRenderer = mockk<com.embabel.common.textio.template.TemplateRenderer>()
    private val mockCompiledTemplate = mockk<CompiledTemplate>()
    private val templateName = "test-template"

    @BeforeEach
    fun setup() {
        every { mockTemplateRenderer.compileLoadedTemplate(templateName) } returns mockCompiledTemplate
    }

    private fun createTemplateOperations(): DelegatingTemplateOperations {
        return DelegatingTemplateOperations(
            delegate = mockDelegate,
            templateName = templateName,
            templateRenderer = mockTemplateRenderer,
        )
    }

    @Nested
    inner class CreateObjectTest {

        @Test
        fun `should render template and delegate to createObject with UserMessage`() {
            val model = mapOf("name" to "World")
            val renderedText = "Hello World"
            val expectedResult = "test result"
            val messagesSlot = slot<List<Message>>()

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(capture(messagesSlot), String::class.java) } returns expectedResult

            val operations = createTemplateOperations()
            val result = operations.createObject(String::class.java, model)

            verify { mockCompiledTemplate.render(model) }
            verify { mockDelegate.createObject(any(), String::class.java) }
            assertEquals(expectedResult, result)

            val capturedMessages = messagesSlot.captured
            assertEquals(1, capturedMessages.size)
            assert(capturedMessages[0] is UserMessage)
        }

        @Test
        fun `should pass correct output class to delegate`() {
            data class TestOutput(val value: String)

            val model = mapOf("key" to "value")
            val renderedText = "test text"
            val expectedResult = TestOutput("result")

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(any(), TestOutput::class.java) } returns expectedResult

            val operations = createTemplateOperations()
            val result = operations.createObject(TestOutput::class.java, model)

            verify { mockDelegate.createObject(any(), TestOutput::class.java) }
            assertEquals(expectedResult, result)
        }
    }

    @Nested
    inner class GenerateTextTest {

        @Test
        fun `should render template and delegate to createObject with String class`() {
            val model = mapOf("key" to "value")
            val renderedText = "rendered template text"
            val expectedResult = "generated text"
            val messagesSlot = slot<List<Message>>()

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(capture(messagesSlot), String::class.java) } returns expectedResult

            val operations = createTemplateOperations()
            val result = operations.generateText(model)

            verify { mockCompiledTemplate.render(model) }
            verify { mockDelegate.createObject(any(), String::class.java) }
            assertEquals(expectedResult, result)

            val capturedMessages = messagesSlot.captured
            assertEquals(1, capturedMessages.size)
            assert(capturedMessages[0] is UserMessage)
        }
    }

    @Nested
    inner class RespondWithSystemPromptTest {

        @Test
        fun `should render template as SystemMessage and delegate to respond`() {
            val model = mapOf("instruction" to "Be helpful")
            val renderedText = "You are a helpful assistant"
            val conversation = mockk<Conversation>()
            val conversationMessages = listOf(UserMessage("What is 2+2?"))
            every { conversation.messages } returns conversationMessages

            val expectedResponse = AssistantMessage("4")
            val messagesSlot = slot<List<Message>>()

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.respond(capture(messagesSlot)) } returns expectedResponse

            val operations = createTemplateOperations()
            val result = operations.respondWithSystemPrompt(conversation, model)

            verify { mockCompiledTemplate.render(model) }
            verify { mockDelegate.respond(any()) }
            assertEquals(expectedResponse, result)

            val capturedMessages = messagesSlot.captured
            assertEquals(2, capturedMessages.size)
            assert(capturedMessages[0] is SystemMessage) { "First message should be SystemMessage" }
            assert(capturedMessages[1] is UserMessage) { "Second message should be from conversation" }
        }

        @Test
        fun `should work with empty model`() {
            val renderedText = "System prompt"
            val conversation = mockk<Conversation>()
            val conversationMessages = listOf(UserMessage("test"))
            every { conversation.messages } returns conversationMessages

            val expectedResponse = AssistantMessage("response")

            every { mockCompiledTemplate.render(emptyMap()) } returns renderedText
            every { mockDelegate.respond(any()) } returns expectedResponse

            val operations = createTemplateOperations()
            val result = operations.respondWithSystemPrompt(conversation, emptyMap())

            verify { mockCompiledTemplate.render(emptyMap()) }
            verify { mockDelegate.respond(any()) }
            assertEquals(expectedResponse, result)
        }
    }
}
