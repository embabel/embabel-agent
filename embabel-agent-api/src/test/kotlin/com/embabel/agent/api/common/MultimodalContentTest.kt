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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the Agent API multimodal functionality
 */
class MultimodalContentTest {

    @Test
    fun `can create AgentImage with different constructors`() {
        // Basic constructor
        val image1 = AgentImage("image/jpeg", byteArrayOf(1, 2, 3))
        assertThat(image1.mimeType).isEqualTo("image/jpeg")
        assertThat(image1.data).containsExactly(1, 2, 3)

        // Using create factory method
        val image2 = AgentImage.create("image/png", byteArrayOf(4, 5, 6))
        assertThat(image2.mimeType).isEqualTo("image/png")
        assertThat(image2.data).containsExactly(4, 5, 6)

        // Using fromBytes
        val image3 = AgentImage.fromBytes("photo.jpg", byteArrayOf(7, 8, 9))
        assertThat(image3.mimeType).isEqualTo("image/jpeg") // Should auto-detect from extension
        assertThat(image3.data).containsExactly(7, 8, 9)
    }

    @Test
    fun `can create multimodal content with factory methods`() {
        // Text only
        val content1 = MultimodalContent.fromText("Text only content")
        assertThat(content1.text).isEqualTo("Text only content")
        assertThat(content1.images).isEmpty()

        // With single image
        val content2 = MultimodalContent.withImage(
            "Describe this:",
            AgentImage("image/png", byteArrayOf(4, 5, 6))
        )
        assertThat(content2.text).isEqualTo("Describe this:")
        assertThat(content2.images).hasSize(1)
        assertThat(content2.images[0].mimeType).isEqualTo("image/png")

        // With multiple images
        val content3 = MultimodalContent.withImages(
            "Multiple images:",
            listOf(
                AgentImage("image/jpeg", byteArrayOf(1, 2, 3)),
                AgentImage("image/png", byteArrayOf(4, 5, 6))
            )
        )
        assertThat(content3.text).isEqualTo("Multiple images:")
        assertThat(content3.images).hasSize(2)
    }

    @Test
    fun `multimodal content builder works correctly`() {
        val content = multimodal()
            .text("Analyze this:")
            .image("image/png", byteArrayOf(10, 11, 12))
            .image("image/jpeg", byteArrayOf(13, 14, 15))
            .build()

        assertThat(content.text).isEqualTo("Analyze this:")
        assertThat(content.images).hasSize(2)
        assertThat(content.images[0].mimeType).isEqualTo("image/png")
        assertThat(content.images[1].mimeType).isEqualTo("image/jpeg")
    }

    @Test
    fun `toContentParts converts correctly`() {
        val content = MultimodalContent(
            text = "Convert this",
            images = listOf(
                AgentImage("image/jpeg", byteArrayOf(1, 2, 3))
            )
        )

        val parts = content.toContentParts()
        assertThat(parts).hasSize(2)
        assertThat(parts[0]).isInstanceOf(com.embabel.chat.TextPart::class.java)
        assertThat(parts[1]).isInstanceOf(com.embabel.chat.ImagePart::class.java)
        assertThat((parts[0] as com.embabel.chat.TextPart).text).isEqualTo("Convert this")
        assertThat((parts[1] as com.embabel.chat.ImagePart).mimeType).isEqualTo("image/jpeg")
        assertThat((parts[1] as com.embabel.chat.ImagePart).data).containsExactly(1, 2, 3)
    }

    @Test
    fun `AgentImage equality works correctly`() {
        val image1 = AgentImage("image/jpeg", byteArrayOf(1, 2, 3))
        val image2 = AgentImage("image/jpeg", byteArrayOf(1, 2, 3))
        val image3 = AgentImage("image/png", byteArrayOf(1, 2, 3))
        val image4 = AgentImage("image/jpeg", byteArrayOf(4, 5, 6))

        assertThat(image1).isEqualTo(image2)
        assertThat(image1).isNotEqualTo(image3)
        assertThat(image1).isNotEqualTo(image4)
    }

    @Test
    fun `fromBytes throws exception for unknown file extension`() {
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            AgentImage.fromBytes("photo.tiff", byteArrayOf(1, 2, 3))
        }

        assertThat(exception.message).contains("Unknown image format: .tiff")
        assertThat(exception.message).contains("Supported formats: jpg, jpeg, png, gif, webp, bmp")
        assertThat(exception.message).contains("AgentImage.create(mimeType, data)")
    }

    @Test
    fun `create allows explicit MIME type for any format`() {
        // Users can bypass format detection with explicit MIME type
        val image = AgentImage.create("image/heic", byteArrayOf(1, 2, 3))
        assertThat(image.mimeType).isEqualTo("image/heic")
        assertThat(image.data).containsExactly(1, 2, 3)
    }
}
