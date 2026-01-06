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
package com.embabel.common.ai.converters.streaming

import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.streaming.ThinkingState
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
    fun `convertStream should filter out malformed JSON treated as thinking continuation`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val invalidJson = "invalid json line"

        // When - malformed JSON is treated as thinking continuation and filtered out
        val result = converter.convertStream(invalidJson)

        // Then - no objects should be emitted (malformed JSON becomes thinking which is filtered)
        val items = result.collectList().block()
        assertNotNull(items)
        assertTrue(items!!.isEmpty())
    }

    @Test
    fun `convertStreamWithThinking should treat malformed JSON as thinking continuation`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val mixedContent = """
            <think>this is fine</think>
            invalid json here
        """.trimIndent()

        // When - malformed JSON becomes thinking continuation
        val result = converter.convertStreamWithThinking(mixedContent)

        // Then - should get 2 thinking events: explicit + continuation
        val events = result.collectList().block()
        assertNotNull(events)
        assertEquals(2, events!!.size)

        assertTrue(events[0] is StreamingEvent.Thinking)
        assertEquals("this is fine", (events[0] as StreamingEvent.Thinking).content)

        assertTrue(events[1] is StreamingEvent.Thinking)
        assertEquals("invalid json here", (events[1] as StreamingEvent.Thinking).content)
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

    @Test
    fun `convertStreamWithThinking should handle multi-line thinking blocks`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val multiLineContent = """
            <think>
            This is a multi-line
            thinking block that spans
            several lines
            </think>
            {"name": "result"}
        """.trimIndent()

        // When
        val result = converter.convertStreamWithThinking(multiLineContent)

        // Then - should get 4 thinking events + 1 object
        val events = result.collectList().block()
        assertNotNull(events)


        assertEquals(6, events.size)  // 5 thinking + 1 object

        // Verify thinking events with proper states
        assertTrue(events[0] is StreamingEvent.Thinking)
        assertEquals("<think>", (events[0] as StreamingEvent.Thinking).content)
        assertEquals(ThinkingState.START, (events[0] as StreamingEvent.Thinking).state)

        assertTrue(events[1] is StreamingEvent.Thinking)
        assertEquals("This is a multi-line", (events[1] as StreamingEvent.Thinking).content)
        assertEquals(ThinkingState.CONTINUATION, (events[1] as StreamingEvent.Thinking).state)

        assertTrue(events[2] is StreamingEvent.Thinking)
        assertEquals("thinking block that spans", (events[2] as StreamingEvent.Thinking).content)
        assertEquals(ThinkingState.CONTINUATION, (events[2] as StreamingEvent.Thinking).state)

        assertTrue(events[3] is StreamingEvent.Thinking)
        assertEquals("several lines", (events[3] as StreamingEvent.Thinking).content)
        assertEquals(ThinkingState.CONTINUATION, (events[3] as StreamingEvent.Thinking).state)

        assertTrue(events[4] is StreamingEvent.Thinking)
        assertEquals("</think>", (events[4] as StreamingEvent.Thinking).content)
        assertEquals(ThinkingState.END, (events[4] as StreamingEvent.Thinking).state)

        // Verify the JSON object event
        assertTrue(events[5] is StreamingEvent.Object<*>)
        assertEquals("result", (events[5] as StreamingEvent.Object<SimpleItem>).item.name)
    }

    @Test
    fun `convertStreamWithThinking should classify thinking states correctly`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val mixedStatesContent = """
            <think>complete single line</think>
            <think>
            starting block
            continuing thought
            </think>
            random text without tags
            {"name": "object"}
            more random text
        """.trimIndent()

        // When
        val result = converter.convertStreamWithThinking(mixedStatesContent)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)


        val thinkingEvents = events.filterIsInstance<StreamingEvent.Thinking>()
        val objectEvents = events.filterIsInstance<StreamingEvent.Object<*>>()


        assertEquals(1, objectEvents.size)
        assertEquals(7, thinkingEvents.size)  // includes "more random text" line

        // Verify specific states
        assertEquals(ThinkingState.BOTH, thinkingEvents[0].state) // complete single line
        assertEquals(ThinkingState.START, thinkingEvents[1].state) // <think>
        assertEquals(ThinkingState.CONTINUATION, thinkingEvents[2].state) // starting block
        assertEquals(ThinkingState.CONTINUATION, thinkingEvents[3].state) // continuing thought
        assertEquals(ThinkingState.END, thinkingEvents[4].state) // </think>
        assertEquals(ThinkingState.CONTINUATION, thinkingEvents[5].state) // random text without tags
        assertEquals(ThinkingState.CONTINUATION, thinkingEvents[6].state) // more random text
    }

    @Test
    fun `convertStreamWithThinking should handle mixed JSON and thinking continuation`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val mixedContent = """
            {"name": "first"}
            some random text
            {"name": "second"}
            invalid json { broken
            {"name": "third"}
        """.trimIndent()

        // When
        val result = converter.convertStreamWithThinking(mixedContent)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)

        val objectEvents = events!!.filterIsInstance<StreamingEvent.Object<*>>()
        val thinkingEvents = events.filterIsInstance<StreamingEvent.Thinking>()

        assertEquals(3, objectEvents.size, "Should get 3 valid JSON objects")
        assertEquals(2, thinkingEvents.size, "Should get 2 thinking continuation events")

        // Verify objects
        assertEquals("first", (objectEvents[0].item as SimpleItem).name)
        assertEquals("second", (objectEvents[1].item as SimpleItem).name)
        assertEquals("third", (objectEvents[2].item as SimpleItem).name)

        // Verify thinking continuations
        assertEquals("some random text", thinkingEvents[0].content)
        assertEquals(ThinkingState.CONTINUATION, thinkingEvents[0].state)
        assertEquals("invalid json { broken", thinkingEvents[1].content)
        assertEquals(ThinkingState.CONTINUATION, thinkingEvents[1].state)
    }

    @Test
    fun `convertStreamWithThinking should support all thinking tag formats`() {
        // Given
        val converter = StreamingJacksonOutputConverter(SimpleItem::class.java, objectMapper)
        val allFormatsContent = """
            <think>standard format</think>
            <analysis>qwen format</analysis>
            <thought>llama format</thought>
            <reasoning>xml reasoning format</reasoning>
            //THINKING: legacy format
        """.trimIndent()

        // When
        val result = converter.convertStreamWithThinking(allFormatsContent)

        // Then
        val events = result.collectList().block()
        assertNotNull(events)
        assertEquals(5, events!!.size)

        val thinkingEvents = events.filterIsInstance<StreamingEvent.Thinking>()
        assertEquals(5, thinkingEvents.size)

        assertEquals("standard format", thinkingEvents[0].content)
        assertEquals("qwen format", thinkingEvents[1].content)
        assertEquals("llama format", thinkingEvents[2].content)
        assertEquals("xml reasoning format", thinkingEvents[3].content)
        assertEquals("legacy format", thinkingEvents[4].content)
    }
}
