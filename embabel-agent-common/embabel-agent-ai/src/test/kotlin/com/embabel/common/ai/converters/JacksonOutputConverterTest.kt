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
package com.embabel.common.ai.converters

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class JacksonOutputConverterTest {

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    data class SimpleObject(
        val name: String,
        val value: Int,
    )

    data class Mention(
        val role: String,
        val span: String,
        val type: String,
    )

    data class Proposition(
        val text: String,
        val mentions: List<Mention>,
        val confidence: Double,
    )

    data class PropositionsResult(
        val propositions: List<Proposition>,
    )

    // Kotlin-specific types
    data class KotlinDataClass(
        val name: String,
        val items: List<String>,
        val metadata: Map<String, Any>,
        val optional: String? = null,
        val defaultValue: Int = 42,
    )

    // Date/time types
    data class DateTimeObject(
        val instant: Instant,
        val localDate: LocalDate,
        val localDateTime: LocalDateTime,
    )

    @Nested
    inner class MalformedEscapedQuotesTests {

        @Test
        fun `parses valid JSON unchanged`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val validJson = """{"name": "test", "value": 42}"""

            val result = converter.convert(validJson)

            assertNotNull(result)
            assertEquals("test", result?.name)
            assertEquals(42, result?.value)
        }

        @Test
        fun `fixes escaped quotes at start and end of string value`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            // Malformed: "name": \"test\",
            val malformedJson = """{"name": \"test\", "value": 42}"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("test", result?.name)
            assertEquals(42, result?.value)
        }

        @Test
        fun `fixes escaped quotes before closing brace`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            // Malformed: "name": \"test\"}
            val malformedJson = """{"value": 42, "name": \"test\"}"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("test", result?.name)
        }

        @Test
        fun `fixes escaped quotes in nested objects`() {
            val converter = JacksonOutputConverter(Proposition::class.java, objectMapper)
            val malformedJson = """{
                "text": "User likes Brahms",
                "mentions": [
                    {
                        "role": "subject",
                        "span": \"User\",
                        "type": "Person"
                    }
                ],
                "confidence": 0.9
            }"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("User likes Brahms", result?.text)
            assertEquals(1, result?.mentions?.size)
            assertEquals("User", result?.mentions?.get(0)?.span)
        }

        @Test
        fun `fixes escaped quotes before closing bracket`() {
            val converter = JacksonOutputConverter(Proposition::class.java, objectMapper)
            val malformedJson = """{
                "text": "Test",
                "mentions": [
                    {
                        "role": "subject",
                        "span": \"value\",
                        "type": \"Person\"
                    }
                ],
                "confidence": 0.9
            }"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("Person", result?.mentions?.get(0)?.type)
        }

        @Test
        fun `fixes real-world LLM output with apostrophes in value`() {
            val converter = JacksonOutputConverter(Proposition::class.java, objectMapper)
            // Real case: "span": \"Glazunov's violin concerto\",
            val malformedJson = """{
                "text": "RJ loves Glazunov's Violin Concerto",
                "mentions": [
                    {
                        "role": "subject",
                        "span": "RJ",
                        "type": "Person"
                    },
                    {
                        "role": "object",
                        "span": \"Glazunov's violin concerto\",
                        "type": "Work"
                    }
                ],
                "confidence": 0.9
            }"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("Glazunov's violin concerto", result?.mentions?.get(1)?.span)
        }

        @Test
        fun `fixes multiple escaped quotes in same JSON`() {
            val converter = JacksonOutputConverter(PropositionsResult::class.java, objectMapper)
            val malformedJson = """{
                "propositions": [
                    {
                        "text": \"First proposition\",
                        "mentions": [],
                        "confidence": 0.9
                    },
                    {
                        "text": \"Second proposition\",
                        "mentions": [],
                        "confidence": 0.8
                    }
                ]
            }"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals(2, result?.propositions?.size)
            assertEquals("First proposition", result?.propositions?.get(0)?.text)
            assertEquals("Second proposition", result?.propositions?.get(1)?.text)
        }

        @Test
        fun `preserves valid escaped quotes inside strings`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            // Valid JSON with escaped quote inside the string value
            val validJson = """{"name": "test \"quoted\" value", "value": 42}"""

            val result = converter.convert(validJson)

            assertNotNull(result)
            assertEquals("test \"quoted\" value", result?.name)
        }

        @Test
        fun `handles mixed valid and malformed escapes`() {
            val converter = JacksonOutputConverter(Proposition::class.java, objectMapper)
            // Mix of valid escaped quotes inside string and malformed at delimiters
            val malformedJson = """{
                "text": \"User said \"hello\" to Bob\",
                "mentions": [],
                "confidence": 0.9
            }"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("User said \"hello\" to Bob", result?.text)
        }

        @Test
        fun `removes markdown code blocks`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val wrappedJson = """```json
{"name": "test", "value": 42}
```"""

            val result = converter.convert(wrappedJson)

            assertNotNull(result)
            assertEquals("test", result?.name)
        }

        @Test
        fun `handles whitespace variations in malformed JSON`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            // Various whitespace patterns
            val malformedJson = """{"name":\"test\", "value": 42}"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("test", result?.name)
        }

        @Test
        fun `fixes escaped quotes with newlines before closing brace`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val malformedJson = """{
                "value": 42,
                "name": \"test\"
            }"""

            val result = converter.convert(malformedJson)

            assertNotNull(result)
            assertEquals("test", result?.name)
        }
    }

    @Nested
    inner class JacksonLenientParsingTests {

        @Test
        fun `handles trailing commas via Jackson`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val jsonWithTrailingComma = """{"name": "test", "value": 42,}"""

            val result = converter.convert(jsonWithTrailingComma)

            assertNotNull(result)
            assertEquals("test", result?.name)
            assertEquals(42, result?.value)
        }

        @Test
        fun `handles single quotes via Jackson`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val jsonWithSingleQuotes = """{'name': 'test', 'value': 42}"""

            val result = converter.convert(jsonWithSingleQuotes)

            assertNotNull(result)
            assertEquals("test", result?.name)
        }

        @Test
        fun `handles unquoted field names via Jackson`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val jsonWithUnquotedFields = """{name: "test", value: 42}"""

            val result = converter.convert(jsonWithUnquotedFields)

            assertNotNull(result)
            assertEquals("test", result?.name)
        }

        @Test
        fun `handles Java-style comments via Jackson`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val jsonWithComments = """{
                "name": "test", /* this is a comment */
                "value": 42 // another comment
            }"""

            val result = converter.convert(jsonWithComments)

            assertNotNull(result)
            assertEquals("test", result?.name)
        }

        @Test
        fun `handles nested trailing commas`() {
            val converter = JacksonOutputConverter(Proposition::class.java, objectMapper)
            val jsonWithTrailingCommas = """{
                "text": "Test",
                "mentions": [
                    {"role": "subject", "span": "User", "type": "Person",},
                ],
                "confidence": 0.9,
            }"""

            val result = converter.convert(jsonWithTrailingCommas)

            assertNotNull(result)
            assertEquals("Test", result?.text)
            assertEquals(1, result?.mentions?.size)
        }

        @Test
        fun `handles mixed lenient features`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            // Single quotes + trailing comma + unquoted field
            val messyJson = """{name: 'test', 'value': 42,}"""

            val result = converter.convert(messyJson)

            assertNotNull(result)
            assertEquals("test", result?.name)
            assertEquals(42, result?.value)
        }
    }

    @Nested
    inner class KotlinAndDateTypeTests {

        @Test
        fun `handles Kotlin data classes with default values`() {
            val converter = JacksonOutputConverter(KotlinDataClass::class.java, objectMapper)
            // Missing optional and defaultValue fields - should use defaults
            val json = """{"name": "test", "items": ["a", "b"], "metadata": {"key": "value"}}"""

            val result = converter.convert(json)

            assertNotNull(result)
            assertEquals("test", result?.name)
            assertEquals(listOf("a", "b"), result?.items)
            assertEquals(mapOf("key" to "value"), result?.metadata)
            assertEquals(null, result?.optional)
            assertEquals(42, result?.defaultValue)
        }

        @Test
        fun `handles Kotlin nullable types`() {
            val converter = JacksonOutputConverter(KotlinDataClass::class.java, objectMapper)
            val json = """{"name": "test", "items": [], "metadata": {}, "optional": "present"}"""

            val result = converter.convert(json)

            assertNotNull(result)
            assertEquals("present", result?.optional)
        }

        @Test
        fun `handles Kotlin collections`() {
            val converter = JacksonOutputConverter(KotlinDataClass::class.java, objectMapper)
            val json = """{"name": "test", "items": ["x", "y", "z"], "metadata": {"a": 1, "b": "two"}}"""

            val result = converter.convert(json)

            assertNotNull(result)
            assertEquals(3, result?.items?.size)
            assertEquals("x", result?.items?.get(0))
            assertEquals(1, result?.metadata?.get("a"))
            assertEquals("two", result?.metadata?.get("b"))
        }

        @Test
        fun `handles Java 8 Instant`() {
            val converter = JacksonOutputConverter(DateTimeObject::class.java, objectMapper)
            val json = """{
                "instant": "2024-01-15T10:30:00Z",
                "localDate": "2024-01-15",
                "localDateTime": "2024-01-15T10:30:00"
            }"""

            val result = converter.convert(json)

            assertNotNull(result)
            assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result?.instant)
            assertEquals(LocalDate.of(2024, 1, 15), result?.localDate)
            assertEquals(LocalDateTime.of(2024, 1, 15, 10, 30, 0), result?.localDateTime)
        }

        @Test
        fun `handles dates with lenient parsing`() {
            val converter = JacksonOutputConverter(DateTimeObject::class.java, objectMapper)
            // Trailing comma after last field
            val json = """{
                "instant": "2024-01-15T10:30:00Z",
                "localDate": "2024-01-15",
                "localDateTime": "2024-01-15T10:30:00",
            }"""

            val result = converter.convert(json)

            assertNotNull(result)
            assertEquals(LocalDate.of(2024, 1, 15), result?.localDate)
        }

        @Test
        fun `handles Kotlin data class with lenient parsing`() {
            val converter = JacksonOutputConverter(KotlinDataClass::class.java, objectMapper)
            // Single quotes + trailing comma
            val json = """{'name': 'test', 'items': ['a',], 'metadata': {},}"""

            val result = converter.convert(json)

            assertNotNull(result)
            assertEquals("test", result?.name)
            assertEquals(listOf("a"), result?.items)
        }

        @Test
        fun `handles nested Kotlin types with malformed quotes`() {
            val converter = JacksonOutputConverter(PropositionsResult::class.java, objectMapper)
            val json = """{
                "propositions": [
                    {
                        "text": \"User's preference\",
                        "mentions": [
                            {"role": "subject", "span": "User", "type": "Person"}
                        ],
                        "confidence": 0.9
                    }
                ]
            }"""

            val result = converter.convert(json)

            assertNotNull(result)
            assertEquals("User's preference", result?.propositions?.get(0)?.text)
        }
    }
}
