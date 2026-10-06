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

import tools.jackson.core.JacksonException
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.kotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class JacksonOutputConverterTest {

    private val objectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

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

    data class KotlinRequiredChild(
        val name: String,
        val note: String?,
    )

    data class KotlinRequiredParent(
        val child: KotlinRequiredChild,
        val title: String,
        val optional: String?,
    )

    @Nested
    inner class SchemaNormalizationTests {

        @Test
        fun `marks Kotlin non-null properties as required`() {
            val converter = JacksonOutputConverter(KotlinRequiredParent::class.java, objectMapper)
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())

            assertThat(schema.requiredFieldNames()).containsExactlyInAnyOrder("child", "title")
            assertThat(schema.path("properties").path("optional").requiredFieldNames()).isEmpty()
            assertThat(schema.path("properties").path("child").requiredFieldNames()).containsExactlyInAnyOrder("name")
        }

        @Test
        fun `can disable required field normalization`() {
            val converter = JacksonOutputConverter(
                KotlinRequiredParent::class.java,
                objectMapper,
                requiredFieldNormalization = RequiredFieldNormalization.DISABLED,
            )
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())

            assertThat(schema.requiredFieldNames()).isEmpty()
            assertThat(schema.path("properties").path("child").requiredFieldNames()).isEmpty()
        }

        @Test
        fun `filtering converter can disable required field normalization`() {
            val converter = FilteringJacksonOutputConverter(
                clazz = KotlinRequiredParent::class.java,
                objectMapper = objectMapper,
                fieldFilter = { true },
                requiredFieldNormalization = RequiredFieldNormalization.DISABLED,
            )
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())

            assertThat(schema.requiredFieldNames()).isEmpty()
            assertThat(schema.path("properties").path("child").requiredFieldNames()).isEmpty()
        }

        @Test
        fun `marks Java primitives and annotations as required while leaving plain references optional`() {
            val javaType = Class.forName("com.embabel.common.ai.converters.JavaStructuredOutputFixtures\$Parent")
                as Class<Any>
            val converter = JacksonOutputConverter(javaType, objectMapper)
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())

            assertThat(schema.requiredFieldNames()).containsExactlyInAnyOrder(
                "primitiveCount",
                "explicitRequired",
                "validatedRequired",
            )
            assertThat(schema.path("properties").path("optionalText").requiredFieldNames()).isEmpty()
            assertThat(schema.path("properties").path("child").requiredFieldNamesOrRefResolved(schema))
                .containsExactlyInAnyOrder("count")
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class MalformedEscapedQuotesTests {

        @ParameterizedTest(name = "{0}")
        @MethodSource("validJson")
        fun `repair returns valid JSON unchanged`(json: String) {
            assertEquals(json, fixMalformedEscapedQuotes(json))
        }

        fun validJson() = listOf(
            // Repro from #1804
            """{"title": "Hello \"World\", \"How\" are you?"}""",
            // Mermaid diagram from #1788
            """{"name": "flowchart LR\n  a[\"A\"]\n  b[\"B\"]\n  a --> b", "value": 42}""",
            """["\"A\""]""",
            """{"name": "test \"quoted\" value", "value": 42}""",
            """{"name": "ends with \" }", "value": 1}""",
            """{"items": ["closes \" ]"], "value": 1}""",
            """{"note": ": \" after colon", "value": 1}""",
            """{"path": "C:\\temp\\file"}""",
            // Output truncated mid-string, e.g. at a token limit
            """{"name": "truncated \"mid""",
            """{"name": "b""" + "\\",
        )

        @ParameterizedTest(name = "{0}")
        @MethodSource("malformedJson")
        fun `repair rewrites only delimiter quotes`(malformed: String, expected: String) {
            assertEquals(expected, fixMalformedEscapedQuotes(malformed))
        }

        fun malformedJson() = listOf(
            Arguments.of(
                """{"name": \"test\", "value": 42}""",
                """{"name": "test", "value": 42}""",
            ),
            Arguments.of(
                """{"value": 42, "name": \"test\"}""",
                """{"value": 42, "name": "test"}""",
            ),
            Arguments.of(
                """{\"name\": \"test\"}""",
                """{"name": "test"}""",
            ),
            Arguments.of(
                """{"text": \"User said \"hello\" to Bob\", "confidence": 0.9}""",
                """{"text": "User said \"hello\" to Bob", "confidence": 0.9}""",
            ),
            Arguments.of(
                "{\"name\": \\\"test\\\"\n}",
                "{\"name\": \"test\"\n}",
            ),
            // Escaped opening quote but plain closing quote
            Arguments.of(
                """{"name": \"test"}""",
                """{"name": "test"}""",
            ),
            Arguments.of(
                """[\"a\", \"b\"]""",
                """["a", "b"]""",
            ),
            Arguments.of(
                """{"a": \"\", "b": 1}""",
                """{"a": "", "b": 1}""",
            ),
        )

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
        fun `preserves valid escaped quotes inside mermaid diagram`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val validJson = """{
                    "name": "flowchart LR\n  a[\"A\"]\n  b[\"B\"]\n  a --> b",
                    "value": 42
                }""".trimIndent()

            // Jackson can handle the valid JSON without any changes
            val actual = objectMapper.readValue(validJson, SimpleObject::class.java)
            assertThat(actual.name).isEqualTo("flowchart LR\n  a[\"A\"]\n  b[\"B\"]\n  a --> b")

            val result = converter.convert(validJson)
            assertThat(result?.name).isEqualTo("flowchart LR\n  a[\"A\"]\n  b[\"B\"]\n  a --> b")
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

        @Test
        fun `handles unquoted CTRL characters`() {
            // Value with unquoted newline character.
            val name = """Hello
World"""
            val messyJson = """{"name":"$name", "value": 42}"""

            // Parse the malformed json string without the lenient mapper.
            val mapper = jacksonObjectMapper()
            val exception = assertThrows<JacksonException> {
                mapper.readValue(messyJson, SimpleObject::class.java)
            }

            // Verify that the assertion is thrown as expected.
            assert(exception.message?.contains("Illegal unquoted character ((CTRL-CHAR, code 10))") == true)

            // Parse the malformed json string with the lenient mapper.
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            val result = converter.convert(messyJson)

            assertNotNull(result)
            assertEquals(name, result?.name)
            assertEquals(42, result?.value)
        }
    }

    @Nested
    inner class ParseFailureTests {

        @Test
        fun `convert throws RuntimeException and logs warn on invalid JSON`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            assertThrows<RuntimeException> {
                converter.convert("this is not json at all")
            }
        }

        @Test
        fun `convert throws RuntimeException on structurally invalid JSON`() {
            val converter = JacksonOutputConverter(SimpleObject::class.java, objectMapper)
            assertThrows<RuntimeException> {
                converter.convert("{ this is completely malformed JSON")
            }
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

    // --- fixtures for @DescribedEnum tests ---

    @DescribedEnum
    enum class Priority {
        @com.fasterxml.jackson.annotation.JsonPropertyDescription("Needs same-day response") URGENT,
        @com.fasterxml.jackson.annotation.JsonPropertyDescription("Standard turnaround")    NORMAL,
        OTHER,  // deliberately un-annotated
    }

    // Enum with @JsonValue — wire value comes from method, not .name()
    @DescribedEnum
    enum class WireValuePriority(private val wire: String) {
        @com.fasterxml.jackson.annotation.JsonPropertyDescription("Needs same-day response")
        URGENT("urgent-wire"),
        @com.fasterxml.jackson.annotation.JsonPropertyDescription("Standard turnaround")
        NORMAL("normal-wire"),
        OTHER("other-wire");

        @com.fasterxml.jackson.annotation.JsonValue
        fun toWire(): String = wire
    }

    // Plain enum — no @DescribedEnum, no @JsonPropertyDescription
    enum class BareEnum { A, B, C }

    // @DescribedEnum present but no @JsonPropertyDescription on any constant
    @DescribedEnum
    enum class NoDescriptions { A, B, C }

    data class PriorityHolder(val priority: Priority)
    data class WireValuePriorityHolder(val priority: WireValuePriority)
    data class BareEnumHolder(val value: BareEnum)
    data class NoDescriptionsHolder(val value: NoDescriptions)
    // Enum nested inside a collection — the shape from issue #2028
    data class ListPriorityHolder(val priorities: List<Priority>)
    // Enum nested inside a record inside a List — Nathan's exact real-world shape:
    //   record ColumnMappingMatcherLlmResult(List<LlmProposedMapping> mappings)
    //   record KnownLlmProposedMapping(AvailableScheduleField scheduleField)
    data class Mapping(val priority: Priority)
    data class DeepHolder(val mappings: List<Mapping>)

    @Nested
    inner class DescribedEnumTests {

        @Test
        fun `bare enum without @DescribedEnum stays as enum array`() {
            val converter = JacksonOutputConverter(BareEnumHolder::class.java, objectMapper)
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())
            val valueNode = schema.path("properties").path("value")
            assertThat(valueNode.has("enum")).isTrue()
            assertThat(valueNode.has("oneOf")).isFalse()
        }

        @Test
        fun `withEnumConstantDescriptions extension emits oneOf with type string and per-constant descriptions`() {
            // Simulate what ChatClientLlmOperations.buildFilteringConverter does when @DescribedEnum is detected
            val converter = object : JacksonOutputConverter<PriorityHolder>(PriorityHolder::class.java, objectMapper) {
                override fun schemaGeneratorConfigBuilder() =
                    super.schemaGeneratorConfigBuilder().withEnumConstantDescriptions(objectMapper)
            }
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())
            val priorityNode = schema.path("properties").path("priority")

            assertThat(priorityNode.has("oneOf")).isTrue()
            assertThat(priorityNode.has("enum")).isFalse()
            // type:string is required so provider schema bridges (e.g. Gemini) can interpret the node
            assertThat(priorityNode.path("type").asText()).isEqualTo("string")

            val oneOf = priorityNode.path("oneOf")
            assertThat(oneOf.isArray).isTrue()
            assertThat(oneOf.size()).isEqualTo(3)

            val urgent = oneOf.first { it.path("const").asText() == "URGENT" }
            assertThat(urgent.path("description").asText()).isEqualTo("Needs same-day response")

            val normal = oneOf.first { it.path("const").asText() == "NORMAL" }
            assertThat(normal.path("description").asText()).isEqualTo("Standard turnaround")

            // un-annotated constant has const but no description key
            val other = oneOf.first { it.path("const").asText() == "OTHER" }
            assertThat(other.has("description")).isFalse()
        }

        @Test
        fun `withEnumConstantDescriptions with @JsonValue uses wire value as const`() {
            val converter = object : JacksonOutputConverter<WireValuePriorityHolder>(WireValuePriorityHolder::class.java, objectMapper) {
                override fun schemaGeneratorConfigBuilder() =
                    super.schemaGeneratorConfigBuilder().withEnumConstantDescriptions(objectMapper)
            }
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())
            val priorityNode = schema.path("properties").path("priority")

            assertThat(priorityNode.has("oneOf")).isTrue()
            assertThat(priorityNode.path("type").asText()).isEqualTo("string")

            val oneOf = priorityNode.path("oneOf")
            // @JsonValue takes precedence — const values are wire strings from toWire(), not .name()
            val urgent = oneOf.first { it.path("const").asText() == "urgent-wire" }
            assertThat(urgent.path("description").asText()).isEqualTo("Needs same-day response")

            val other = oneOf.first { it.path("const").asText() == "other-wire" }
            assertThat(other.has("description")).isFalse()
        }

        @Test
        fun `enum without @DescribedEnum is not affected by withEnumConstantDescriptions`() {
            // BareEnum has no @DescribedEnum and no @JsonPropertyDescription — provider returns null,
            // victools falls back to default bare enum array regardless of the extension being installed.
            val converter = object : JacksonOutputConverter<BareEnumHolder>(BareEnumHolder::class.java, objectMapper) {
                override fun schemaGeneratorConfigBuilder() =
                    super.schemaGeneratorConfigBuilder().withEnumConstantDescriptions(objectMapper)
            }
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())
            val valueNode = schema.path("properties").path("value")
            assertThat(valueNode.has("enum")).isTrue()
            assertThat(valueNode.has("oneOf")).isFalse()
        }

        @Test
        fun `enum with @DescribedEnum but no @JsonPropertyDescription on any constant is not affected`() {
            // @DescribedEnum is present but none of the constants carry @JsonPropertyDescription.
            // The provider returns null — no oneOf is emitted.
            val converter = object : JacksonOutputConverter<NoDescriptionsHolder>(NoDescriptionsHolder::class.java, objectMapper) {
                override fun schemaGeneratorConfigBuilder() =
                    super.schemaGeneratorConfigBuilder().withEnumConstantDescriptions(objectMapper)
            }
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())
            val valueNode = schema.path("properties").path("value")
            assertThat(valueNode.has("enum")).isTrue()
            assertThat(valueNode.has("oneOf")).isFalse()
        }

        @Test
        fun `@DescribedEnum enum nested inside a List emits oneOf for the array items`() {
            // Reproduces the real-world shape from issue #2028:
            // record Holder(List<Priority> priorities) — the enum is not a direct field,
            // it is the element type of a collection. Victools resolves the element type
            // and calls the provider, so the annotation check inside the provider catches it.
            val converter = object : JacksonOutputConverter<ListPriorityHolder>(ListPriorityHolder::class.java, objectMapper) {
                override fun schemaGeneratorConfigBuilder() =
                    super.schemaGeneratorConfigBuilder().withEnumConstantDescriptions(objectMapper)
            }
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())
            val itemsNode = schema.path("properties").path("priorities").path("items")

            assertThat(itemsNode.has("oneOf")).isTrue()
            assertThat(itemsNode.has("enum")).isFalse()
            assertThat(itemsNode.path("type").asText()).isEqualTo("string")

            val oneOf = itemsNode.path("oneOf")
            val urgent = oneOf.first { it.path("const").asText() == "URGENT" }
            assertThat(urgent.path("description").asText()).isEqualTo("Needs same-day response")
        }

        @Test
        fun `@DescribedEnum enum inside a record inside a List emits oneOf — Nathan's exact shape`() {
            // Reproduces the deepest shape from issue #2028:
            // record DeepHolder(List<Mapping> mappings)
            // record Mapping(Priority priority)
            // The enum is two levels deep — inside a nested data class inside a List.
            val converter = object : JacksonOutputConverter<DeepHolder>(DeepHolder::class.java, objectMapper) {
                override fun schemaGeneratorConfigBuilder() =
                    super.schemaGeneratorConfigBuilder().withEnumConstantDescriptions(objectMapper)
            }
            val schema = jacksonObjectMapper().readTree(converter.getJsonSchema())

            // Navigate: properties -> mappings -> items -> properties -> priority
            val priorityNode = schema
                .path("properties").path("mappings")
                .path("items")
                .path("properties").path("priority")

            assertThat(priorityNode.has("oneOf")).isTrue()
            assertThat(priorityNode.has("enum")).isFalse()
            assertThat(priorityNode.path("type").asText()).isEqualTo("string")

            val oneOf = priorityNode.path("oneOf")
            val urgent = oneOf.first { it.path("const").asText() == "URGENT" }
            assertThat(urgent.path("description").asText()).isEqualTo("Needs same-day response")
        }
    }
}

private fun tools.jackson.databind.JsonNode.requiredFieldNamesOrRefResolved(
    rootSchema: tools.jackson.databind.JsonNode,
): Set<String> {
    val ref = get("\$ref")?.takeIf { it.isString }?.asString()
    if (ref != null && ref.startsWith("#/")) {
        val resolved = ref
            .removePrefix("#/")
            .split('/')
            .fold(rootSchema) { current, token -> current.get(token) ?: return emptySet() }
        return resolved.requiredFieldNames()
    }

    return requiredFieldNames()
}
