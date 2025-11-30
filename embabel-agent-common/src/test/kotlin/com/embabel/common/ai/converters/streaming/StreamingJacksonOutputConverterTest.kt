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
package com.embabel.common.ai.converters.streaming

import com.embabel.common.core.streaming.StreamingEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StreamingJacksonOutputConverterTest {

    private val objectMapper = jacksonObjectMapper()

    data class SimpleItem(val name: String)

    data class Person(
        val name: String,
        val age: Int,
        val email: String,
        val address: String
    )

    @Test
    fun `getFormat should request JSONL format instead of single JSON`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)

        // When
        val format = converter.getFormat()

        // Then
        assertTrue(format.contains("JSONL (JSON Lines) format"))
        assertTrue(format.contains("Each line must contain exactly one JSON object"))
        assertTrue(format.contains("Do not include markdown code blocks or wrap responses in arrays"))
        assertTrue(format.contains("<think>"))
    }

    @Test
    fun `getFormat should inherit schema from parent JacksonOutputConverter`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)

        // When
        val format = converter.getFormat()

        // Then
        assertTrue(format.contains("JSON Schema"))
        assertTrue(format.contains("name")) // Should contain schema for SimpleItem
    }

    @Test
    fun `convertStream should handle empty input gracefully`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)

        // When
        val result = converter.convertStream("")

        // Then
        val items = result.collectList().block()
        assertNotNull(items)
        assertTrue(items!!.isEmpty())
    }

    @Test
    fun `convertStream should filter blank lines`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val jsonlWithBlanks = """
            {"name": "item1"}

            {"name": "item2"}

        """.trimIndent()

        // When
        val result = converter.convertStream(jsonlWithBlanks)

        // Then
        val items = result.collectList().block()
        assertNotNull(items)
        assertEquals(2, items!!.size)
    }

    @Test
    fun `convertStream should delegate to parent convert method for each line`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val validJsonl = """
            {"name": "test1"}
            {"name": "test2"}
        """.trimIndent()

        // When
        val result = converter.convertStream(validJsonl)

        // Then
        val items = result.collectList().block()
        assertNotNull(items)
        assertEquals(2, items!!.size)
        assertEquals("test1", items[0].name)
        assertEquals("test2", items[1].name)
    }

    @Test
    fun `convertStreamWithThinking should parse thinking lines correctly`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val mixedContent = """
            <think>analyzing requirement</think>
            {"name": "result"}
        """.trimIndent()

        // When
        val result = converter.convertStreamWithThinking(mixedContent)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)
        assertEquals(2, events!!.size)

        assertTrue(events[0] is StreamingEvent.Thinking)
        assertEquals("analyzing requirement", (events[0] as StreamingEvent.Thinking).content)

        assertTrue(events[1] is StreamingEvent.Object<*>)
        assertEquals("result", (events[1] as StreamingEvent.Object<SimpleItem>).item.name)
    }

    @Test
    fun `convertStreamWithThinking should handle mixed content with multiple thinking blocks`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val content = """
            <think>step 1</think>
            <analysis>step 2</analysis>
            {"name": "final"}
        """.trimIndent()

        // When
        val result = converter.convertStreamWithThinking(content)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)
        assertEquals(3, events!!.size) // 2 thinking + 1 object
    }

    @Test
    fun `convertStreamWithThinking should extract thinking content correctly`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val thinkingLine = "<think>detailed analysis here</think>"

        // When
        val result = converter.convertStreamWithThinking(thinkingLine)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)
        assertEquals(1, events!!.size)

        assertTrue(events[0] is StreamingEvent.Thinking)
        assertEquals("detailed analysis here", (events[0] as StreamingEvent.Thinking).content)
    }

    @Test
    fun `convertStreamWithThinking should support legacy THINKING prefix format`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val legacyThinkingLine = "//THINKING: legacy format support"

        // When
        val result = converter.convertStreamWithThinking(legacyThinkingLine)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)
        assertEquals(1, events!!.size)

        assertTrue(events[0] is StreamingEvent.Thinking)
        assertEquals("legacy format support", (events[0] as StreamingEvent.Thinking).content)
    }

    @Test
    fun `convertStreamWithThinking should delegate object parsing to parent`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val objectLine = """{"name": "testItem"}"""

        // When
        val result = converter.convertStreamWithThinking(objectLine)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)
        assertEquals(1, events!!.size)

        assertTrue(events[0] is StreamingEvent.Object<*>)
        assertEquals("testItem", (events[0] as StreamingEvent.Object<SimpleItem>).item.name)
    }

    @Test
    fun `convertStream should fail on malformed JSON`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val invalidJson = "invalid json line"

        // When & Then - Should error on malformed JSON
        assertThrows<RuntimeException> {
            converter.convertStream(invalidJson).collectList().block()
        }
    }

    @Test
    fun `convertStreamWithThinking should fail on malformed JSON in mixed content`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val mixedContent = """
            <think>this is fine</think>
            invalid json here
        """.trimIndent()

        // When & Then - Should error on malformed JSON
        assertThrows<RuntimeException> {
            converter.convertStreamWithThinking(mixedContent).collectList().block()
        }
    }

    @Test
    fun `streaming converter should include only specified properties in schema`() {
        // Given
        val converter = StreamingJacksonOutputConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = { it == "name" || it == "age" }
        )

        // When
        val schema = converter.jsonSchema

        // Then
        assertTrue(schema.contains("name"))
        assertTrue(schema.contains("age"))
        assertFalse(schema.contains("email"))
        assertFalse(schema.contains("address"))
    }

    @Test
    fun `streaming converter should filter properties in multi-object JSONL`() {
        // Given - converter that only allows name and age
        val converter = StreamingJacksonOutputConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = { it == "name" || it == "age" }
        )

        // Create JSONL with multiple Person objects containing all fields
        val jsonlInput = """
            {"name": "Alice", "age": 30, "email": "alice@test.com", "address": "123 Main St"}
            {"name": "Bob", "age": 25, "email": "bob@test.com", "address": "456 Oak Ave"}
        """.trimIndent()

        // When
        val result = converter.convertStream(jsonlInput)

        // Then
        val people = result.collectList().block()
        assertNotNull(people)
        assertEquals(2, people!!.size)

        // Verify first person has only name and age (filtered properties should be default/null)
        assertEquals("Alice", people[0].name)
        assertEquals(30, people[0].age)
        // Note: Jackson will use default constructor values for filtered fields

        // Verify second person
        assertEquals("Bob", people[1].name)
        assertEquals(25, people[1].age)
    }

    @Test
    fun `streaming converter format should include filtered schema only`() {
        // Given
        val converter = StreamingJacksonOutputConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = { it == "name" }
        )

        // When
        val format = converter.getFormat()

        // Then
        assertTrue(format.contains("name"))
        assertFalse(format.contains("email"))
        assertFalse(format.contains("address"))
    }

    @Test
    fun `streaming converter should handle filtering with actual streaming for multiple objects`() {
        // Given - converter that only allows name and age
        val converter = StreamingJacksonOutputConverter(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = { it == "name" || it == "age" }
        )

        val jsonlInput = """
            {"name": "Alice", "age": 30, "email": "alice@test.com", "address": "123 Main St"}
            {"name": "Bob", "age": 25, "email": "bob@test.com", "address": "456 Oak Ave"}
        """.trimIndent()

        // When - use actual streaming with subscribe
        val streamedPeople = mutableListOf<Person>()
        var completedSuccessfully = false

        converter.convertStream(jsonlInput)
            .doOnNext { person ->
                streamedPeople.add(person)
                println("Streamed person: ${person.name}, age: ${person.age}")
            }
            .doOnComplete { completedSuccessfully = true }
            .subscribe()

        // Give stream time to complete (in real async scenario would use proper waiting)
        Thread.sleep(100)

        // Then - verify streaming with filtering worked
        assertTrue(completedSuccessfully, "Stream should complete successfully")
        assertEquals(2, streamedPeople.size, "Should receive 2 filtered persons")

        // Verify first person (Alice) - filtered properties only
        assertEquals("Alice", streamedPeople[0].name)
        assertEquals(30, streamedPeople[0].age)

        // Verify second person (Bob) - filtered properties only
        assertEquals("Bob", streamedPeople[1].name)
        assertEquals(25, streamedPeople[1].age)

        // Verify streaming preserved order
        assertEquals("Alice", streamedPeople[0].name)
        assertEquals("Bob", streamedPeople[1].name)
    }
}
