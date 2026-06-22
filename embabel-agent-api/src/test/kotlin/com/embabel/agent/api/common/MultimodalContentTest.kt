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

import com.embabel.chat.DocumentPart
import com.embabel.chat.ImagePart
import com.embabel.chat.TextPart
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the Agent API multimodal functionality
 */
class MultimodalContentTest {

    @TempDir
    lateinit var tempDir: Path

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
    fun `can create AgentDocument with different constructors`() {
        val document1 = AgentDocument("application/pdf", byteArrayOf(1, 2, 3), "report.pdf")
        assertThat(document1.mimeType).isEqualTo("application/pdf")
        assertThat(document1.data).containsExactly(1, 2, 3)
        assertThat(document1.filename).isEqualTo("report.pdf")

        val document2 = AgentDocument.create("text/csv", byteArrayOf(4, 5, 6), "data.csv")
        assertThat(document2.mimeType).isEqualTo("text/csv")
        assertThat(document2.data).containsExactly(4, 5, 6)
        assertThat(document2.filename).isEqualTo("data.csv")

        val document3 = AgentDocument.fromBytes("workbook.ods", byteArrayOf(7, 8, 9))
        assertThat(document3.mimeType).isEqualTo("application/vnd.oasis.opendocument.spreadsheet")
        assertThat(document3.data).containsExactly(7, 8, 9)
        assertThat(document3.filename).isEqualTo("workbook.ods")
    }

    @Test
    fun `can create AgentDocument from file and path`() {
        val file = tempDir.resolve("report.pdf")
        Files.write(file, byteArrayOf(1, 2, 3))

        val fromPath = AgentDocument.fromPath(file)
        assertThat(fromPath.mimeType).isEqualTo("application/pdf")
        assertThat(fromPath.data).containsExactly(1, 2, 3)
        assertThat(fromPath.filename).isEqualTo("report.pdf")

        val fromFile = AgentDocument.fromFile(file.toFile())
        assertThat(fromFile).isEqualTo(fromPath)
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
        assertThat(content2.documents).isEmpty()
        assertThat(content2.images[0].mimeType).isEqualTo("image/png")

        // With single document
        val documentContent = MultimodalContent.withDocument(
            "Read this:",
            AgentDocument("application/pdf", byteArrayOf(7, 8, 9), "report.pdf")
        )
        assertThat(documentContent.text).isEqualTo("Read this:")
        assertThat(documentContent.images).isEmpty()
        assertThat(documentContent.documents).hasSize(1)
        assertThat(documentContent.documents[0].filename).isEqualTo("report.pdf")

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

        // With multiple documents
        val content4 = MultimodalContent.withDocuments(
            "Multiple documents:",
            listOf(
                AgentDocument("application/pdf", byteArrayOf(1, 2, 3), "report.pdf"),
                AgentDocument("text/csv", byteArrayOf(4, 5, 6), "data.csv")
            )
        )
        assertThat(content4.text).isEqualTo("Multiple documents:")
        assertThat(content4.documents).hasSize(2)
    }

    @Test
    fun `multimodal content builder works correctly`() {
        val content = multimodal()
            .text("Analyze this:")
            .image("image/png", byteArrayOf(10, 11, 12))
            .image("image/jpeg", byteArrayOf(13, 14, 15))
            .document("application/pdf", byteArrayOf(16, 17, 18), "report.pdf")
            .build()

        assertThat(content.text).isEqualTo("Analyze this:")
        assertThat(content.images).hasSize(2)
        assertThat(content.documents).hasSize(1)
        assertThat(content.images[0].mimeType).isEqualTo("image/png")
        assertThat(content.images[1].mimeType).isEqualTo("image/jpeg")
        assertThat(content.documents[0].filename).isEqualTo("report.pdf")
    }

    @Test
    fun `toContentParts converts correctly`() {
        val content = MultimodalContent(
            text = "Convert this",
            images = listOf(
                AgentImage("image/jpeg", byteArrayOf(1, 2, 3))
            ),
            documents = listOf(
                AgentDocument("application/pdf", byteArrayOf(4, 5, 6), "report.pdf")
            )
        )

        val parts = content.toContentParts()
        assertThat(parts).hasSize(3)
        assertThat(parts[0]).isInstanceOf(TextPart::class.java)
        assertThat(parts[1]).isInstanceOf(ImagePart::class.java)
        assertThat(parts[2]).isInstanceOf(DocumentPart::class.java)
        assertThat((parts[0] as TextPart).text).isEqualTo("Convert this")
        assertThat((parts[1] as ImagePart).mimeType).isEqualTo("image/jpeg")
        assertThat((parts[1] as ImagePart).data).containsExactly(1, 2, 3)
        assertThat((parts[2] as DocumentPart).mimeType).isEqualTo("application/pdf")
        assertThat((parts[2] as DocumentPart).data).containsExactly(4, 5, 6)
        assertThat((parts[2] as DocumentPart).filename).isEqualTo("report.pdf")
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
    fun `AgentDocument equality works correctly`() {
        val document1 = AgentDocument("application/pdf", byteArrayOf(1, 2, 3), "report.pdf")
        val document2 = AgentDocument("application/pdf", byteArrayOf(1, 2, 3), "report.pdf")
        val document3 = AgentDocument("text/csv", byteArrayOf(1, 2, 3), "report.pdf")
        val document4 = AgentDocument("application/pdf", byteArrayOf(4, 5, 6), "report.pdf")
        val document5 = AgentDocument("application/pdf", byteArrayOf(1, 2, 3), "other.pdf")

        assertThat(document1).isEqualTo(document2)
        assertThat(document1).isNotEqualTo(document3)
        assertThat(document1).isNotEqualTo(document4)
        assertThat(document1).isNotEqualTo(document5)
    }

    @Test
    fun `fromBytes throws exception for unknown file extension`() {
        val exception = assertThrows<IllegalArgumentException> {
            AgentImage.fromBytes("photo.tiff", byteArrayOf(1, 2, 3))
        }

        assertThat(exception.message).contains("Unsupported image extension: tiff")
    }

    @Test
    fun `AgentDocument fromBytes throws exception for unknown file extension`() {
        val exception = assertThrows<IllegalArgumentException> {
            AgentDocument.fromBytes("archive.zip", byteArrayOf(1, 2, 3))
        }

        assertThat(exception.message).contains("Unsupported document extension: zip")
    }

    @Test
    fun `create allows explicit MIME type for any format`() {
        // Users can bypass format detection with explicit MIME type
        val image = AgentImage.create("image/heic", byteArrayOf(1, 2, 3))
        assertThat(image.mimeType).isEqualTo("image/heic")
        assertThat(image.data).containsExactly(1, 2, 3)
    }
}
