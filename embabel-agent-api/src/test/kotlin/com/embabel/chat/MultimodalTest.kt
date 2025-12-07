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
package com.embabel.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for multimodal message functionality
 */
class MultimodalTest {

    @Test
    fun `can create ContentPart types`() {
        // Test TextPart
        val textPart = TextPart("Hello, world!")
        assertThat(textPart.text).isEqualTo("Hello, world!")

        // Test ImagePart
        val imagePart = ImagePart("image/jpeg", byteArrayOf(1, 2, 3))
        assertThat(imagePart.mimeType).isEqualTo("image/jpeg")
        assertThat(imagePart.data).containsExactly(1, 2, 3)
    }

    @Test
    fun `UserMessage supports text-only constructor for backward compatibility`() {
        val message = UserMessage("Hello!")
        assertThat(message.content).isEqualTo("Hello!")
        assertThat(message.textContent).isEqualTo("Hello!")
        assertThat(message.parts).hasSize(1)
        assertThat(message.parts[0]).isInstanceOf(TextPart::class.java)
        assertThat(message.isMultimodal).isFalse()
    }

    @Test
    fun `UserMessage supports multipart constructor`() {
        val message = UserMessage(
            listOf(
                TextPart("Describe this:"),
                ImagePart("image/png", byteArrayOf(10, 11, 12))
            )
        )

        assertThat(message.content).isEqualTo("Describe this:")
        assertThat(message.textContent).isEqualTo("Describe this:")
        assertThat(message.parts).hasSize(2)
        assertThat(message.imageParts).hasSize(1)
        assertThat(message.isMultimodal).isTrue()
    }

    @Test
    fun `UserMessageBuilder creates multimodal messages`() {
        val message = userMessage()
            .text("What's in this image?")
            .image("image/jpeg", byteArrayOf(1, 2, 3))
            .build()

        assertThat(message.textContent).isEqualTo("What's in this image?")
        assertThat(message.imageParts).hasSize(1)
        assertThat(message.imageParts[0].mimeType).isEqualTo("image/jpeg")
        assertThat(message.isMultimodal).isTrue()
    }

    @Test
    fun `AssistantMessage remains text-only`() {
        val message = AssistantMessage("I see a cat in the image.")
        assertThat(message.content).isEqualTo("I see a cat in the image.")
        assertThat(message.parts).hasSize(1)
        assertThat(message.parts[0]).isInstanceOf(TextPart::class.java)
        assertThat(message.isMultimodal).isFalse()
    }

    @Test
    fun `SystemMessage remains text-only`() {
        val message = SystemMessage("You are a helpful assistant.")
        assertThat(message.content).isEqualTo("You are a helpful assistant.")
        assertThat(message.parts).hasSize(1)
        assertThat(message.parts[0]).isInstanceOf(TextPart::class.java)
        assertThat(message.isMultimodal).isFalse()
    }

    @Test
    fun `Message with multiple text parts concatenates content`() {
        val message = UserMessage(
            listOf(
                TextPart("Hello "),
                TextPart("world!"),
                ImagePart("image/png", byteArrayOf(1, 2, 3))
            )
        )

        assertThat(message.textContent).isEqualTo("Hello world!")
        assertThat(message.content).isEqualTo("Hello world!")
    }
}
