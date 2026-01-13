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
package com.embabel.agent.spi.support.springai

import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.ImagePart
import com.embabel.chat.SystemMessage
import com.embabel.chat.TextPart
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.messages.SystemMessage as SpringAiSystemMessage
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage

/**
 * Tests for converting Embabel messages to Spring AI messages, including multimodal content
 */
class MessageConversionTest {

    @Test
    fun `converts text-only UserMessage`() {
        val message = UserMessage("Hello, world!")

        val springAiMessage = message.toSpringAiMessage()

        assertThat(springAiMessage).isInstanceOf(SpringAiUserMessage::class.java)
        assertThat(springAiMessage.text).isEqualTo("Hello, world!")
    }

    @Test
    fun `converts text-only AssistantMessage`() {
        val message = AssistantMessage("I can help with that.")

        val springAiMessage = message.toSpringAiMessage()

        assertThat(springAiMessage).isInstanceOf(SpringAiAssistantMessage::class.java)
        assertThat(springAiMessage.text).isEqualTo("I can help with that.")
    }

    @Test
    fun `converts text-only SystemMessage`() {
        val message = SystemMessage("You are a helpful assistant.")

        val springAiMessage = message.toSpringAiMessage()

        assertThat(springAiMessage).isInstanceOf(SpringAiSystemMessage::class.java)
        assertThat(springAiMessage.text).isEqualTo("You are a helpful assistant.")
    }

    @Test
    fun `converts UserMessage with single image`() {
        val message = UserMessage(
            listOf(
                TextPart("What's in this image?"),
                ImagePart("image/jpeg", byteArrayOf(1, 2, 3))
            )
        )

        val springAiMessage = message.toSpringAiMessage() as SpringAiUserMessage

        assertThat(springAiMessage.text).isEqualTo("What's in this image?")
        assertThat(springAiMessage.media).hasSize(1)

        val media = springAiMessage.media[0]
        assertThat(media.mimeType.toString()).isEqualTo("image/jpeg")
        // Verify the resource exists
        assertThat(media.data).isNotNull()
    }

    @Test
    fun `converts UserMessage with multiple images`() {
        val message = UserMessage(
            listOf(
                TextPart("Compare these images:"),
                ImagePart("image/jpeg", byteArrayOf(1, 2, 3)),
                ImagePart("image/png", byteArrayOf(4, 5, 6))
            )
        )

        val springAiMessage = message.toSpringAiMessage() as SpringAiUserMessage

        assertThat(springAiMessage.text).isEqualTo("Compare these images:")
        assertThat(springAiMessage.media).hasSize(2)

        assertThat(springAiMessage.media[0].mimeType.toString()).isEqualTo("image/jpeg")
        assertThat(springAiMessage.media[0].data).isNotNull()

        assertThat(springAiMessage.media[1].mimeType.toString()).isEqualTo("image/png")
        assertThat(springAiMessage.media[1].data).isNotNull()
    }

    @Test
    fun `converts UserMessage with only images no text`() {
        val message = UserMessage(
            listOf(
                ImagePart("image/jpeg", byteArrayOf(1, 2, 3))
            )
        )

        val springAiMessage = message.toSpringAiMessage() as SpringAiUserMessage

        // Spring AI requires non-empty text, so we add a space for image-only messages
        assertThat(springAiMessage.text).isEqualTo(" ")
        assertThat(springAiMessage.media).hasSize(1)
        assertThat(springAiMessage.media[0].data).isNotNull()
    }

    @Test
    fun `converts UserMessage with multiple text parts and images`() {
        val message = UserMessage(
            listOf(
                TextPart("Look at "),
                TextPart("this image:"),
                ImagePart("image/png", byteArrayOf(10, 11, 12))
            )
        )

        val springAiMessage = message.toSpringAiMessage() as SpringAiUserMessage

        assertThat(springAiMessage.text).isEqualTo("Look at this image:")
        assertThat(springAiMessage.media).hasSize(1)
    }

    @Test
    fun `converts various image MIME types correctly`() {
        val testCases = mapOf(
            "image/jpeg" to "image/jpeg",
            "image/png" to "image/png",
            "image/gif" to "image/gif",
            "image/webp" to "image/webp"
        )

        testCases.forEach { (inputMimeType, expectedMimeType) ->
            val message = UserMessage(
                listOf(
                    ImagePart(inputMimeType, byteArrayOf(1, 2, 3))
                )
            )

            val springAiMessage = message.toSpringAiMessage() as SpringAiUserMessage
            assertThat(springAiMessage.media[0].mimeType.toString()).isEqualTo(expectedMimeType)
        }
    }

    @Test
    fun `backward compatibility - text-only UserMessage has no media`() {
        val message = UserMessage("Just text")

        val springAiMessage = message.toSpringAiMessage() as SpringAiUserMessage

        assertThat(springAiMessage.text).isEqualTo("Just text")
        assertThat(springAiMessage.media).isEmpty()
    }

    @Nested
    inner class ToolMessageConversionTests {

        @Test
        fun `converts AssistantMessageWithToolCalls to Spring AI message`() {
            val message = AssistantMessageWithToolCalls(
                content = "Let me check that for you",
                toolCalls = listOf(
                    ToolCall("call-1", "get_weather", """{"location": "NYC"}""")
                )
            )

            val springAiMessage = message.toSpringAiMessage()

            assertThat(springAiMessage).isInstanceOf(SpringAiAssistantMessage::class.java)
            assertThat(springAiMessage.text).isEqualTo("Let me check that for you")
        }

        @Test
        fun `converts ToolResultMessage to Spring AI message`() {
            val message = ToolResultMessage(
                toolCallId = "call-1",
                toolName = "get_weather",
                content = """{"temperature": 72}"""
            )

            val springAiMessage = message.toSpringAiMessage()

            assertThat(springAiMessage).isInstanceOf(SpringAiAssistantMessage::class.java)
            assertThat(springAiMessage.text).contains("get_weather")
            assertThat(springAiMessage.text).contains("""{"temperature": 72}""")
        }
    }

    @Nested
    inner class ToEmbabelMessageTests {

        @Test
        fun `converts Spring AI AssistantMessage without tool calls`() {
            val springMessage = SpringAiAssistantMessage("Hello from assistant")

            val embabelMessage = springMessage.toEmbabelMessage()

            assertThat(embabelMessage).isInstanceOf(AssistantMessage::class.java)
            assertThat(embabelMessage.textContent).isEqualTo("Hello from assistant")
        }

        @Test
        fun `throws exception when converting Spring AI AssistantMessage with empty text`() {
            val springMessage = SpringAiAssistantMessage("")

            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                springMessage.toEmbabelMessage()
            }
        }

        @Test
        fun `converts Spring AI AssistantMessage with tool calls`() {
            val toolCalls = listOf(
                SpringAiAssistantMessage.ToolCall("call-1", "tool", "get_weather", """{"location": "NYC"}"""),
                SpringAiAssistantMessage.ToolCall("call-2", "tool", "get_time", """{"timezone": "EST"}""")
            )
            val springMessage = mockk<SpringAiAssistantMessage> {
                every { text } returns "Checking..."
                every { getToolCalls() } returns toolCalls
            }

            val embabelMessage = springMessage.toEmbabelMessage()

            assertThat(embabelMessage).isInstanceOf(AssistantMessageWithToolCalls::class.java)
            val messageWithCalls = embabelMessage as AssistantMessageWithToolCalls
            assertThat(messageWithCalls.textContent).isEqualTo("Checking...")
            assertThat(messageWithCalls.toolCalls).hasSize(2)
            assertThat(messageWithCalls.toolCalls[0].id).isEqualTo("call-1")
            assertThat(messageWithCalls.toolCalls[0].name).isEqualTo("get_weather")
            assertThat(messageWithCalls.toolCalls[0].arguments).isEqualTo("""{"location": "NYC"}""")
            assertThat(messageWithCalls.toolCalls[1].id).isEqualTo("call-2")
            assertThat(messageWithCalls.toolCalls[1].name).isEqualTo("get_time")
        }

        @Test
        fun `converts Spring AI AssistantMessage with empty tool calls list`() {
            val springMessage = mockk<SpringAiAssistantMessage> {
                every { text } returns "No tools"
                every { getToolCalls() } returns emptyList()
            }

            val embabelMessage = springMessage.toEmbabelMessage()

            assertThat(embabelMessage).isInstanceOf(AssistantMessage::class.java)
            assertThat(embabelMessage).isNotInstanceOf(AssistantMessageWithToolCalls::class.java)
        }

        @Test
        fun `converts Spring AI AssistantMessage with null tool calls`() {
            val springMessage = SpringAiAssistantMessage("Simple message")

            val embabelMessage = springMessage.toEmbabelMessage()

            assertThat(embabelMessage).isInstanceOf(AssistantMessage::class.java)
            assertThat(embabelMessage).isNotInstanceOf(AssistantMessageWithToolCalls::class.java)
        }
    }
}
