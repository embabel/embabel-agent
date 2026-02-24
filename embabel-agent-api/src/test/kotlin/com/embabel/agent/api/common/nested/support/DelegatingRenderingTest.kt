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

import com.embabel.agent.api.common.support.DelegatingRendering
import com.embabel.agent.api.common.support.PromptExecutionDelegate
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.textio.template.CompiledTemplate
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DelegatingRenderingTest {

    private val mockDelegate = mockk<PromptExecutionDelegate>()
    private val mockTemplateRenderer = mockk<com.embabel.common.textio.template.TemplateRenderer>()
    private val mockCompiledTemplate = mockk<CompiledTemplate>()
    private val templateName = "test-template"

    @BeforeEach
    fun setup() {
        every { mockDelegate.templateRenderer } returns mockTemplateRenderer
        every { mockTemplateRenderer.compileLoadedTemplate(templateName) } returns mockCompiledTemplate
    }

    private fun createTemplateOperations(): DelegatingRendering {
        return DelegatingRendering(
            delegate = mockDelegate,
            templateName = templateName,
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

            verify { mockDelegate.templateRenderer }
            verify { mockTemplateRenderer.compileLoadedTemplate(templateName) }
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

            verify { mockDelegate.templateRenderer }
            verify { mockTemplateRenderer.compileLoadedTemplate(templateName) }
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

            verify { mockDelegate.templateRenderer }
            verify { mockTemplateRenderer.compileLoadedTemplate(templateName) }
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

            val expectedResponse = "4"
            val messagesSlot = slot<List<Message>>()

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(capture(messagesSlot), String::class.java) } returns "4"

            val operations = createTemplateOperations()
            val result = operations.respondWithSystemPrompt(conversation, model)

            verify { mockDelegate.templateRenderer }
            verify { mockTemplateRenderer.compileLoadedTemplate(templateName) }
            verify { mockCompiledTemplate.render(model) }
            verify { mockDelegate.createObject(messagesSlot.captured, String::class.java) }
            assertEquals(expectedResponse, result.content)

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

            val expectedResponse = "response"

            every { mockCompiledTemplate.render(emptyMap()) } returns renderedText
            every { mockDelegate.createObject(any(), String::class.java) } returns expectedResponse

            val operations = createTemplateOperations()
            val result = operations.respondWithSystemPrompt(conversation, emptyMap())

            verify { mockDelegate.templateRenderer }
            verify { mockTemplateRenderer.compileLoadedTemplate(templateName) }
            verify { mockCompiledTemplate.render(emptyMap()) }
            verify { mockDelegate.createObject(any(), String::class.java) }
            assertEquals(expectedResponse, result.content)
        }
    }

    @Nested
    inner class RespondWithTriggerTest {

        @Test
        fun `should include system prompt, conversation messages, and trigger as final UserMessage`() {
            val model = mapOf("persona" to "Friendly assistant")
            val renderedText = "You are a friendly assistant"
            val conversation = mockk<Conversation>()
            val conversationMessages = listOf(
                UserMessage("earlier message"),
                AssistantMessage("earlier response"),
            )
            every { conversation.messages } returns conversationMessages

            val triggerPrompt = "Greet the new user Alice"
            val expectedResponse = "Welcome Alice!"
            val messagesSlot = slot<List<Message>>()

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(capture(messagesSlot), String::class.java) } returns expectedResponse

            val operations = createTemplateOperations()
            val result = operations.respondWithTrigger(conversation, triggerPrompt, model)

            assertEquals(expectedResponse, result.content)

            val capturedMessages = messagesSlot.captured
            assertEquals(4, capturedMessages.size)
            assert(capturedMessages[0] is SystemMessage) { "First message should be SystemMessage" }
            assertEquals(renderedText, capturedMessages[0].content)
            assert(capturedMessages[1] is UserMessage) { "Second should be conversation UserMessage" }
            assert(capturedMessages[2] is AssistantMessage) { "Third should be conversation AssistantMessage" }
            assert(capturedMessages[3] is UserMessage) { "Fourth should be trigger UserMessage" }
            assertEquals(triggerPrompt, capturedMessages[3].content)
        }

        @Test
        fun `should work with empty conversation`() {
            val model = mapOf("persona" to "Helper")
            val renderedText = "You are a helper"
            val conversation = mockk<Conversation>()
            every { conversation.messages } returns emptyList()

            val triggerPrompt = "Say hello"
            val expectedResponse = "Hello!"
            val messagesSlot = slot<List<Message>>()

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(capture(messagesSlot), String::class.java) } returns expectedResponse

            val operations = createTemplateOperations()
            val result = operations.respondWithTrigger(conversation, triggerPrompt, model)

            assertEquals(expectedResponse, result.content)

            val capturedMessages = messagesSlot.captured
            assertEquals(2, capturedMessages.size)
            assert(capturedMessages[0] is SystemMessage) { "First should be SystemMessage" }
            assert(capturedMessages[1] is UserMessage) { "Second should be trigger UserMessage" }
            assertEquals(triggerPrompt, capturedMessages[1].content)
        }

        @Test
        fun `trigger prompt should not be stored in conversation`() {
            val model = emptyMap<String, Any>()
            val renderedText = "System prompt"
            val conversation = mockk<Conversation>()
            val conversationMessages = listOf(UserMessage("hello"))
            every { conversation.messages } returns conversationMessages

            every { mockCompiledTemplate.render(model) } returns renderedText
            every { mockDelegate.createObject(any(), String::class.java) } returns "response"

            val operations = createTemplateOperations()
            operations.respondWithTrigger(conversation, "trigger text", model)

            // Verify that addMessage was never called on the conversation —
            // the trigger prompt should only be in the LLM call, not stored
            verify(exactly = 0) { conversation.addMessage(any()) }
        }
    }
}
