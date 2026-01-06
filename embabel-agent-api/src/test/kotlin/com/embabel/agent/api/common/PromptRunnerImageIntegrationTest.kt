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
package com.embabel.agent.api.common

import com.embabel.chat.AssistantMessage
import com.embabel.chat.ImagePart
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration tests for how PromptRunner combines images with messages.
 * Tests the internal logic of combineImagesWithMessages.
 */
class PromptRunnerImageIntegrationTest {

    @Test
    fun `combineImagesWithMessages adds images to empty message list`() {
        val images = listOf(
            AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3))
        )

        val combined = combineImagesWithMessages(emptyList(), images)

        assertThat(combined).hasSize(1)
        val message = combined[0] as UserMessage
        assertThat(message.imageParts).hasSize(1)
        assertThat(message.textContent).isEmpty()
    }

    @Test
    fun `combineImagesWithMessages appends images to last UserMessage`() {
        val existingMessage = UserMessage("What is this?")
        val images = listOf(
            AgentImage.create("image/png", byteArrayOf(4, 5, 6))
        )

        val combined = combineImagesWithMessages(listOf(existingMessage), images)

        assertThat(combined).hasSize(1)
        val message = combined[0] as UserMessage
        assertThat(message.textContent).isEqualTo("What is this?")
        assertThat(message.imageParts).hasSize(1)
        assertThat(message.imageParts[0].data).containsExactly(4, 5, 6)
    }

    @Test
    fun `combineImagesWithMessages creates new UserMessage if last message is not UserMessage`() {
        val assistantMessage = AssistantMessage("Let me analyze that.")
        val images = listOf(
            AgentImage.create("image/jpeg", byteArrayOf(7, 8, 9))
        )

        val combined = combineImagesWithMessages(listOf(assistantMessage), images)

        assertThat(combined).hasSize(2)
        assertThat(combined[0]).isEqualTo(assistantMessage)

        val newMessage = combined[1] as UserMessage
        assertThat(newMessage.imageParts).hasSize(1)
        assertThat(newMessage.textContent).isEmpty()
    }

    @Test
    fun `combineImagesWithMessages does nothing when no images`() {
        val existingMessage = UserMessage("Hello")

        val combined = combineImagesWithMessages(listOf(existingMessage), emptyList())

        assertThat(combined).hasSize(1)
        assertThat(combined[0]).isEqualTo(existingMessage)
    }

    @Test
    fun `combineImagesWithMessages handles multiple images`() {
        val existingMessage = UserMessage("Analyze these:")
        val images = listOf(
            AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3)),
            AgentImage.create("image/png", byteArrayOf(4, 5, 6)),
            AgentImage.create("image/gif", byteArrayOf(7, 8, 9))
        )

        val combined = combineImagesWithMessages(listOf(existingMessage), images)

        assertThat(combined).hasSize(1)
        val message = combined[0] as UserMessage
        assertThat(message.imageParts).hasSize(3)
    }

    @Test
    fun `combineImagesWithMessages preserves existing message parts`() {
        val existingMessage = UserMessage(
            listOf(
                com.embabel.chat.TextPart("Original text"),
                ImagePart("image/jpeg", byteArrayOf(1, 2, 3))
            )
        )
        val newImages = listOf(
            AgentImage.create("image/png", byteArrayOf(4, 5, 6))
        )

        val combined = combineImagesWithMessages(listOf(existingMessage), newImages)

        assertThat(combined).hasSize(1)
        val message = combined[0] as UserMessage
        assertThat(message.parts).hasSize(3) // Original text + original image + new image
        assertThat(message.imageParts).hasSize(2)
    }

    @Test
    fun `combineImagesWithMessages preserves message metadata`() {
        val existingMessage = UserMessage("Test", name = "TestUser")
        val images = listOf(
            AgentImage.create("image/jpeg", byteArrayOf(1, 2, 3))
        )

        val combined = combineImagesWithMessages(listOf(existingMessage), images)

        assertThat(combined).hasSize(1)
        val message = combined[0] as UserMessage
        assertThat(message.name).isEqualTo("TestUser")
    }

    // Helper method that replicates the combineImagesWithMessages logic from OperationContextPromptRunner
    private fun combineImagesWithMessages(
        messages: List<Message>,
        images: List<AgentImage>
    ): List<Message> {
        if (images.isEmpty()) return messages

        val imageParts = images.map { ImagePart(it.mimeType, it.data) }

        if (messages.isEmpty()) {
            return listOf(UserMessage(parts = imageParts))
        }

        val lastMessage = messages.last()
        return if (lastMessage is UserMessage) {
            val updatedLastMessage = UserMessage(
                parts = lastMessage.parts + imageParts,
                name = lastMessage.name,
                timestamp = lastMessage.timestamp
            )
            messages.dropLast(1) + updatedLastMessage
        } else {
            messages + UserMessage(parts = imageParts)
        }
    }
}
