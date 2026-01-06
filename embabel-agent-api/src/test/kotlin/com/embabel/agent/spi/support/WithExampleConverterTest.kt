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
package com.embabel.agent.spi.support

import com.embabel.agent.domain.library.Person
import com.embabel.agent.spi.support.springai.WithExampleConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.converter.BeanOutputConverter
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WithExampleConverterTest {
    private val validJson = "{\"name\": \"test person\"}"
    private val invalidJson = "{name: invalid json}"
    private val personClass = Person::class.java
    private fun makeConverter(ifPossible: Boolean, generateExamples: Boolean) =
        WithExampleConverter(
            BeanOutputConverter<Person>(personClass),
            personClass,
            ifPossible = ifPossible,
            generateExamples = generateExamples
        )

    @Nested
    inner class IfPossibleFalseGenerateExamplesFalse {
        private val converter = makeConverter(false, false)

        @Test
        fun `test valid JSON conversion`() {
            val result = converter.convert(validJson)
            assertNotNull(result)
            assertEquals("test person", result!!.name)
        }

        @Test
        fun `test invalid JSON conversion`() {
            try {
                val result = converter.convert(invalidJson)
                assertNull(result)
            } catch (e: Exception) {
                // Expected behavior - Jackson throws JsonParseException for invalid JSON
                assertTrue(e.message?.contains("Unexpected character") ?: false)
            }
        }

        @Test
        fun `format string does not contain example`() {
            val format = converter.getFormat()
            assertFalse(format.contains("Example"), "Format should not contain any example")
            assertFalse(format.contains("Examples:"), "Format should not contain multiple examples")
            assertFalse(format.contains("success:"), "Format should not contain success label")
            assertFalse(format.contains("failure:"), "Format should not contain failure label")
        }
    }

    @Nested
    inner class IfPossibleFalseGenerateExamplesTrue {
        private val converter = makeConverter(false, true)

        @Test
        fun `test valid JSON conversion`() {
            val result = converter.convert(validJson)
            assertNotNull(result)
            assertEquals("test person", result!!.name)
        }

        @Test
        fun `test invalid JSON conversion`() {
            try {
                val result = converter.convert(invalidJson)
                assertNull(result)
            } catch (e: Exception) {
                // Expected behavior - Jackson throws JsonParseException for invalid JSON
                assertTrue(e.message?.contains("Unexpected character") ?: false)
            }
        }

        @Test
        fun `format string contains single example and JSON structure`() {
            val format = converter.getFormat()
            assertTrue(format.contains("Example:"), "Format should contain single example header")
            assertTrue(format.contains("{"), "Format should contain JSON opening brace")
            assertTrue(format.contains("}"), "Format should contain JSON closing brace")
            assertFalse(format.contains("Examples:"), "Format should not contain multiple examples header")
            assertFalse(format.contains("success:"), "Format should not contain success label")
            assertFalse(format.contains("failure:"), "Format should not contain failure label")
        }
    }

    @Nested
    inner class IfPossibleTrueGenerateExamplesFalse {
        private val converter = makeConverter(true, false)

        @Test
        fun `test valid JSON conversion`() {
            val result = converter.convert(validJson)
            assertNotNull(result)
            assertEquals("test person", result.name)
        }

        @Test
        fun `test invalid JSON conversion`() {
            try {
                val result = converter.convert(invalidJson)
                assertNull(result)
            } catch (e: Exception) {
                // Expected behavior - Jackson throws JsonParseException for invalid JSON
                assertTrue(e.message?.contains("Unexpected character") ?: false)
            }
        }

        @Test
        fun `format string does not contain any example`() {
            val format = converter.getFormat()
            assertFalse(format.contains("Example"), "Format should not contain example header")
            assertFalse(format.contains("Examples:"), "Format should not contain multiple examples header")
            assertFalse(format.contains("success:"), "Format should not contain success label")
            assertFalse(format.contains("failure:"), "Format should not contain failure label")
        }
    }

    @Nested
    inner class IfPossibleTrueGenerateExamplesTrue {
        private val converter = makeConverter(true, true)

        @Test
        fun `test valid JSON conversion`() {
            val result = converter.convert(validJson)
            assertNotNull(result)
            assertEquals("test person", result.name)
        }

        @Test
        fun `test invalid JSON conversion`() {
            try {
                val result = converter.convert(invalidJson)
                assertNull(result)
            } catch (e: Exception) {
                // Expected behavior - Jackson throws JsonParseException for invalid JSON
                assertTrue(e.message?.contains("Unexpected character") == true)
            }
        }

        @Test
        fun `format string contains multiple examples and correct structure`() {
            val format = converter.getFormat()
            assertTrue(format.contains("Examples:"), "Format should contain multiple examples header")
            assertTrue(format.contains("success:"), "Format should contain success label")
            assertTrue(format.contains("failure:"), "Format should contain failure label")
            assertTrue(format.contains("\"success\""), "Format should contain 'success' field in JSON")
            assertTrue(format.contains("\"failure\""), "Format should contain 'failure' field in JSON")
            assertTrue(format.contains("Insufficient context"), "Format should contain failure message")
        }
    }
}
