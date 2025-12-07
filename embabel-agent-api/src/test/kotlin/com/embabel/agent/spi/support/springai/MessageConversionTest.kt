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
package com.embabel.agent.spi.support.springai

import com.embabel.chat.AssistantMessage
import com.embabel.chat.ImagePart
import com.embabel.chat.SystemMessage
import com.embabel.chat.TextPart
import com.embabel.chat.UserMessage
import org.assertj.core.api.Assertions.assertThat
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
}
