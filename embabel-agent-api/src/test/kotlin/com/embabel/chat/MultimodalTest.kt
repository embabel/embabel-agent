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
package com.embabel.chat

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for multimodal message functionality
 */
class MultimodalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `can create ContentPart types`() {
        // Test TextPart
        val textPart = TextPart("Hello, world!")
        assertThat(textPart.text).isEqualTo("Hello, world!")

        // Test ImagePart
        val imagePart = ImagePart("image/jpeg", byteArrayOf(1, 2, 3))
        assertThat(imagePart.mimeType).isEqualTo("image/jpeg")
        assertThat(imagePart.data).containsExactly(1, 2, 3)

        val documentPart = DocumentPart("application/pdf", byteArrayOf(4, 5, 6), "brief.pdf")
        assertThat(documentPart.mimeType).isEqualTo("application/pdf")
        assertThat(documentPart.data).containsExactly(4, 5, 6)
        assertThat(documentPart.filename).isEqualTo("brief.pdf")
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
    fun `UserMessage supports document parts`() {
        val message = UserMessage(
            listOf(
                TextPart("Summarize this:"),
                DocumentPart("application/pdf", byteArrayOf(10, 11, 12), "report.pdf")
            )
        )

        assertThat(message.content).isEqualTo("Summarize this:")
        assertThat(message.textContent).isEqualTo("Summarize this:")
        assertThat(message.parts).hasSize(2)
        assertThat(message.documentParts).hasSize(1)
        assertThat(message.mediaParts).hasSize(1)
        assertThat(message.documentParts[0].filename).isEqualTo("report.pdf")
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

    @Nested
    inner class UserMessageBuilderDocumentTests {

        @Test
        fun `UserMessageBuilder creates document messages`() {
            val message = userMessage()
                .text("Read this document")
                .document("application/pdf", byteArrayOf(1, 2, 3), "contract.pdf")
                .build()

            assertThat(message.textContent).isEqualTo("Read this document")
            assertThat(message.documentParts).hasSize(1)
            assertThat(message.documentParts[0].mimeType).isEqualTo("application/pdf")
            assertThat(message.documentParts[0].filename).isEqualTo("contract.pdf")
            assertThat(message.mediaParts).hasSize(1)
            assertThat(message.isMultimodal).isTrue()
        }

        @Test
        fun `UserMessageBuilder combines text image and document parts`() {
            val message = userMessage()
                .text("Use these inputs:")
                .image("image/png", byteArrayOf(1, 2, 3))
                .document("application/pdf", byteArrayOf(4, 5, 6), "brief.pdf")
                .build()

            assertThat(message.textContent).isEqualTo("Use these inputs:")
            assertThat(message.parts).containsExactly(
                TextPart("Use these inputs:"),
                ImagePart("image/png", byteArrayOf(1, 2, 3)),
                DocumentPart("application/pdf", byteArrayOf(4, 5, 6), "brief.pdf"),
            )
            assertThat(message.imageParts).hasSize(1)
            assertThat(message.documentParts).hasSize(1)
            assertThat(message.mediaParts).hasSize(2)
            assertThat(message.isMultimodal).isTrue()
        }

        @Test
        fun `UserMessageBuilder creates document messages from files`() {
            val file = tempDir.resolve("workbook.ods")
            Files.write(file, byteArrayOf(1, 2, 3))

            val message = userMessage()
                .text("Read this spreadsheet")
                .document(file)
                .build()

            assertThat(message.textContent).isEqualTo("Read this spreadsheet")
            assertThat(message.documentParts).hasSize(1)
            assertThat(message.documentParts[0].mimeType)
                .isEqualTo("application/vnd.oasis.opendocument.spreadsheet")
            assertThat(message.documentParts[0].filename).isEqualTo("workbook.ods")
            assertThat(message.documentParts[0].data).containsExactly(1, 2, 3)
        }

        @Test
        fun `UserMessageBuilder creates document messages from paths`() {
            val path = tempDir.resolve("report.pdf")
            Files.write(path, byteArrayOf(4, 5, 6))

            val message = userMessage()
                .text("Read this report")
                .document(path)
                .build()

            assertThat(message.documentParts).hasSize(1)
            assertThat(message.documentParts[0].mimeType).isEqualTo("application/pdf")
            assertThat(message.documentParts[0].filename).isEqualTo("report.pdf")
            assertThat(message.documentParts[0].data).containsExactly(4, 5, 6)
        }

        @Test
        fun `UserMessageBuilder rejects unsupported document file extensions`() {
            val path = tempDir.resolve("archive.zip")
            Files.write(path, byteArrayOf(1, 2, 3))

            assertThatThrownBy {
                userMessage().document(path)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Unsupported document extension: zip")
        }
    }

    @Test
    fun `DocumentPart rejects invalid input`() {
        assertThatThrownBy {
            DocumentPart("application/octet-stream", byteArrayOf(1, 2, 3), "payload.bin")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid document MIME type")

        assertThatThrownBy {
            DocumentPart("application/pdf", byteArrayOf(), "empty.pdf")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Document data cannot be empty")

        assertThatThrownBy {
            DocumentPart("application/pdf", byteArrayOf(1), "")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Document filename cannot be blank")
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
                ImagePart("image/png", byteArrayOf(1, 2, 3)),
                DocumentPart("application/pdf", byteArrayOf(4, 5, 6), "notes.pdf")
            )
        )

        assertThat(message.textContent).isEqualTo("Hello world!")
        assertThat(message.content).isEqualTo("Hello world!")
        assertThat(message.mediaParts).hasSize(2)
    }
}
