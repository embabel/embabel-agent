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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MessageSerializationTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

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
}
