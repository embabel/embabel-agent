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
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempFile

class MessageSerializationTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private data class PartsEnvelope(
        val parts: List<ContentPart>,
    )

    @Nested
    inner class ContentPartSerialization {

        @Test
        fun `deserializes text part without explicit type metadata`() {
            val json = """
                {
                  "text": "Hello, world!"
                }
            """.trimIndent()

            val part = objectMapper.readValue(json, ContentPart::class.java)

            assertThat(part).isEqualTo(TextPart("Hello, world!"))
        }

        @Test
        fun `deserializes image part without explicit type metadata`() {
            val json = """
                {
                  "mimeType": "image/png",
                  "data": "AQID"
                }
            """.trimIndent()

            val part = objectMapper.readValue(json, ContentPart::class.java)

            assertThat(part).isEqualTo(ImagePart("image/png", byteArrayOf(1, 2, 3)))
        }

        @Test
        fun `deserializes pdf document part by MIME type`() {
            val json = """
                {
                  "mimeType": "application/pdf",
                  "data": "AQID"
                }
            """.trimIndent()

            val part = objectMapper.readValue(json, ContentPart::class.java)

            assertThat(part).isEqualTo(DocumentPart("application/pdf", byteArrayOf(1, 2, 3)))
        }

        @Test
        fun `deserializes null document filename as null`() {
            val json = """
                {
                  "mimeType": "application/pdf",
                  "data": "AQID",
                  "filename": null
                }
            """.trimIndent()

            val part = objectMapper.readValue(json, ContentPart::class.java)

            assertThat(part).isEqualTo(DocumentPart("application/pdf", byteArrayOf(1, 2, 3), null))
            assertThat((part as DocumentPart).filename).isNull()
        }

        @Test
        fun `deserializes xlsx document part with filename by MIME type`() {
            val json = """
                {
                  "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                  "data": "AQID",
                  "filename": "workbook.xlsx"
                }
            """.trimIndent()

            val part = objectMapper.readValue(json, ContentPart::class.java)

            assertThat(part).isEqualTo(
                DocumentPart(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    byteArrayOf(1, 2, 3),
                    "workbook.xlsx"
                )
            )
        }

        @Test
        fun `deserializes open document spreadsheet part by MIME type`() {
            val json = """
                {
                  "mimeType": "application/vnd.oasis.opendocument.spreadsheet",
                  "data": "AQID",
                  "filename": "workbook.ods"
                }
            """.trimIndent()

            val part = objectMapper.readValue(json, ContentPart::class.java)

            assertThat(part).isEqualTo(
                DocumentPart(
                    "application/vnd.oasis.opendocument.spreadsheet",
                    byteArrayOf(1, 2, 3),
                    "workbook.ods"
                )
            )
        }

        @Test
        fun `rejects unsupported media MIME type`() {
            val json = """
                {
                  "mimeType": "application/octet-stream",
                  "data": "AQID"
                }
            """.trimIndent()

            assertThatThrownBy {
                objectMapper.readValue(json, ContentPart::class.java)
            }.hasMessageContaining("Unsupported media MIME type: application/octet-stream")
        }

        @Test
        fun `rejects content part without text or MIME type`() {
            val json = """
                {
                  "data": "AQID"
                }
            """.trimIndent()

            assertThatThrownBy {
                objectMapper.readValue(json, ContentPart::class.java)
            }.hasMessageContaining("Content part must contain 'text' or 'mimeType'")
        }

        @Test
        fun `rejects media content part without data`() {
            val json = """
                {
                  "mimeType": "application/pdf"
                }
            """.trimIndent()

            assertThatThrownBy {
                objectMapper.readValue(json, ContentPart::class.java)
            }.hasMessageContaining("Media content part must contain 'data'")
        }

        @Test
        fun `serializes text part without synthetic type field`() {
            val json = objectMapper.writeValueAsString(TextPart("Hello"))

            assertThat(json).contains("\"text\":\"Hello\"")
            assertThat(json).doesNotContain("@type")
        }
    }

    @Nested
    inner class PartsEnvelopeSerialization {

        @Test
        fun `deserializes object containing mixed content parts`() {
            val json = """
                {
                  "parts": [
                    {
                      "text": "Describe this image: "
                    },
                    {
                      "mimeType": "image/png",
                      "data": "AQID"
                    },
                    {
                      "mimeType": "application/pdf",
                      "data": "BAUG",
                      "filename": "report.pdf"
                    },
                    {
                      "text": "and summarize it."
                    }
                  ]
                }
            """.trimIndent()

            val deserialized = objectMapper.readValue(json, PartsEnvelope::class.java)

            assertThat(deserialized.parts).containsExactly(
                TextPart("Describe this image: "),
                ImagePart("image/png", byteArrayOf(1, 2, 3)),
                DocumentPart("application/pdf", byteArrayOf(4, 5, 6), "report.pdf"),
                TextPart("and summarize it.")
            )
        }

        @Test
        fun `serializes object containing content parts without synthetic type fields`() {
            val envelope = PartsEnvelope(
                parts = listOf(
                    TextPart("Look at "),
                    ImagePart("image/jpeg", byteArrayOf(4, 5, 6)),
                    DocumentPart("application/pdf", byteArrayOf(7, 8, 9), "report.pdf")
                ),
            )

            val json = objectMapper.writeValueAsString(envelope)
            val deserialized = objectMapper.readValue(json, PartsEnvelope::class.java)

            assertThat(json).contains("\"parts\"")
            assertThat(json).contains("\"text\":\"Look at \"")
            assertThat(json).contains("\"mimeType\":\"image/jpeg\"")
            assertThat(json).contains("\"mimeType\":\"application/pdf\"")
            assertThat(json).contains("\"filename\":\"report.pdf\"")
            assertThat(json).doesNotContain("@type")
            assertThat(deserialized.parts).containsExactlyElementsOf(envelope.parts)
        }
    }

    @Nested
    inner class UserMessageSerialization {

        @Test
        fun `UserMessage with DurableAsset round-trips`() {
            val durableAsset = DurableAsset(
                id = "asset-1",
                name = "report.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1024,
                contentHash = "abcdef1234567890",
                storageUri = "storage://bucket/report.pdf",
                timestamp = Instant.parse("2026-10-14T00:00:00Z"),
            )
            val original = UserMessage(
                parts = listOf(TextPart("Here is the document")),
                name = "Alice",
                assets = listOf(durableAsset),
                timestamp = Instant.parse("2026-10-14T00:00:00Z"),
            )

            val json = objectMapper.writeValueAsString(original)
            val restored = objectMapper.readValue(json, UserMessage::class.java)

            assertThat(restored.content).isEqualTo(original.content)
            assertThat(restored.name).isEqualTo(original.name)
            assertThat(restored.timestamp).isEqualTo(original.timestamp)
            assertThat(restored.assets).hasSize(1)
            val restoredAsset = restored.assets.single() as DurableAsset
            assertThat(restoredAsset).isEqualTo(durableAsset)
        }

        @Test
        fun `UserMessage with fromBytes MaterializableAsset round-trips`() {
            val bytesAsset = MaterializableAsset.fromBytes(
                name = "data.csv",
                data = "col1,col2\n1,2".toByteArray(),
                mimeType = "text/csv",
                id = "asset-bytes-1",
                timestamp = Instant.parse("2026-10-14T00:00:00Z"),
                description = "CSV data",
            )
            val original = UserMessage(
                parts = listOf(TextPart("Here is the CSV")),
                assets = listOf(bytesAsset),
                timestamp = Instant.parse("2026-10-14T00:00:00Z"),
            )

            val json = objectMapper.writeValueAsString(original)
            val restored = objectMapper.readValue(json, UserMessage::class.java)

            assertThat(restored.content).isEqualTo(original.content)
            assertThat(restored.assets).hasSize(1)
            val restoredAsset = restored.assets.single() as ByteArrayMaterializableAsset
            assertThat(restoredAsset.name).isEqualTo("data.csv")
            assertThat(restoredAsset.mimeType).isEqualTo("text/csv")
            assertThat(restoredAsset.id).isEqualTo("asset-bytes-1")
            assertThat(restoredAsset.data).isEqualTo("col1,col2\n1,2".toByteArray())
            assertThat(restoredAsset.openStream().readBytes()).isEqualTo(bytesAsset.openStream().readBytes())
        }

        @Test
        fun `UserMessage with fromPath MaterializableAsset round-trips`() {
            val tempFile = createTempFile("test-asset", ".txt")
            tempFile.toFile().deleteOnExit()
            Files.writeString(tempFile, "sample text content")

            val pathAsset = MaterializableAsset.fromPath(
                path = tempFile,
                name = "sample.txt",
                mimeType = "text/plain",
                id = "asset-path-1",
                timestamp = Instant.parse("2026-10-14T00:00:00Z"),
                description = "Sample text file",
            )
            val original = UserMessage(
                parts = listOf(TextPart("Attached file")),
                assets = listOf(pathAsset),
                timestamp = Instant.parse("2026-10-14T00:00:00Z"),
            )

            val json = objectMapper.writeValueAsString(original)
            val restored = objectMapper.readValue(json, UserMessage::class.java)

            assertThat(restored.content).isEqualTo(original.content)
            assertThat(restored.assets).hasSize(1)
            val restoredAsset = restored.assets.single() as PathMaterializableAsset
            assertThat(restoredAsset.path).isEqualTo(tempFile)
            assertThat(restoredAsset.name).isEqualTo("sample.txt")
            assertThat(restoredAsset.mimeType).isEqualTo("text/plain")
            assertThat(restoredAsset.id).isEqualTo("asset-path-1")
            assertThat(restoredAsset.openStream().readBytes()).isEqualTo("sample text content".toByteArray())
        }

        @Test
        fun `UserMessage deserializes derived getter properties on strict reader without failure`() {
            val strictReader = objectMapper.reader(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .forType(UserMessage::class.java)

            val original = UserMessage(listOf(TextPart("Hello")))
            val json = objectMapper.writeValueAsString(original)

            val restored: UserMessage = strictReader.readValue(json)
            assertThat(restored.content).isEqualTo("Hello")
        }

        @Test
        fun `UserMessage fails to deserialize when unknown property is present on strict reader`() {
            val strictReader = objectMapper.reader(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .forType(UserMessage::class.java)

            val json = """
                {
                    "parts": [{"text": "Hello"}],
                    "unknownField": "unexpected"
                }
            """.trimIndent()

            assertThatThrownBy {
                strictReader.readValue<UserMessage>(json)
            }.isInstanceOf(tools.jackson.databind.exc.UnrecognizedPropertyException::class.java)
        }
    }
}
