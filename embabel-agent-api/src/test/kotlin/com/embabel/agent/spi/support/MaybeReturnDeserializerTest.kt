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

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [MaybeReturnDeserializer] covering all edge cases.
 */
class MaybeReturnDeserializerTest {

    private val objectMapper = jacksonObjectMapper()

    data class TestData(val name: String, val value: Int)

    /**
     * Data class with required non-primitive fields to test MissingKotlinParameterException.
     * Unlike primitives (Int, Boolean), non-nullable String fields without defaults
     * will throw MissingKotlinParameterException when missing from JSON.
     */
    data class TestDataWithRequiredFields(val name: String, val description: String)

    @Nested
    inner class ValidSuccessCases {

        @Test
        fun `deserializes valid success with object`() {
            val json = """{"success": {"name": "test", "value": 42}}"""
            val result = deserialize<TestData>(json)

            assertNotNull(result.success)
            assertNull(result.failure)
            assertEquals("test", result.success?.name)
            assertEquals(42, result.success?.value)
        }

        @Test
        fun `deserializes valid success with primitive string`() {
            val json = """{"success": "hello"}"""
            val result = deserialize<String>(json)

            assertEquals("hello", result.success)
            assertNull(result.failure)
        }

        @Test
        fun `deserializes valid success with primitive number`() {
            val json = """{"success": 123}"""
            val result = deserialize<Int>(json)

            assertEquals(123, result.success)
            assertNull(result.failure)
        }
    }

    @Nested
    inner class ValidFailureCases {

        @Test
        fun `deserializes valid failure with message`() {
            val json = """{"failure": "Something went wrong"}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Something went wrong", result.failure)
        }
    }

    @Nested
    inner class SuccessEdgeCases {

        @Test
        fun `success null returns failure`() {
            val json = """{"success": null}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Success value was null", result.failure)
        }

        @Test
        fun `success with missing required fields returns failure`() {
            // Use TestDataWithRequiredFields which has non-primitive required fields
            // that will throw MissingKotlinParameterException when missing
            val json = """{"success": {"name": "test"}}"""
            val result = deserialize<TestDataWithRequiredFields>(json)

            assertNull(result.success)
            assertTrue(result.failure?.contains("Missing required field") == true)
        }

        @Test
        fun `success with empty object returns failure for data class`() {
            // Use TestDataWithRequiredFields which has non-primitive required fields
            val json = """{"success": {}}"""
            val result = deserialize<TestDataWithRequiredFields>(json)

            assertNull(result.success)
            assertTrue(result.failure?.contains("Missing required field") == true)
        }

        @Test
        fun `success with invalid type returns failure`() {
            val json = """{"success": "not a number"}"""
            val result = deserialize<Int>(json)

            assertNull(result.success)
            assertNotNull(result.failure)
        }
    }

    @Nested
    inner class FailureEdgeCases {

        @Test
        fun `failure null returns failure with message`() {
            val json = """{"failure": null}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Failure indicated but no message provided", result.failure)
        }

        @Test
        fun `failure with empty string returns failure`() {
            val json = """{"failure": ""}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Failure message was empty", result.failure)
        }

        @Test
        fun `failure with blank string returns failure`() {
            val json = """{"failure": "   "}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Failure message was empty", result.failure)
        }

        @Test
        fun `failure with number returns failure`() {
            val json = """{"failure": 123}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Failure message must be a string", result.failure)
        }

        @Test
        fun `failure with boolean returns failure`() {
            val json = """{"failure": true}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Failure message must be a string", result.failure)
        }

        @Test
        fun `failure with object returns failure`() {
            val json = """{"failure": {"error": "details"}}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Failure message must be a string", result.failure)
        }

        @Test
        fun `failure with array returns failure`() {
            val json = """{"failure": ["error1", "error2"]}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Failure message must be a string", result.failure)
        }
    }

    @Nested
    inner class BothFieldsCases {

        @Test
        fun `both success and failure present returns failure`() {
            val json = """{"success": "value", "failure": "error"}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Both success and failure provided", result.failure)
        }

        @Test
        fun `both fields present even with null values returns failure`() {
            val json = """{"success": null, "failure": null}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Both success and failure provided", result.failure)
        }
    }

    @Nested
    inner class NeitherFieldCases {

        @Test
        fun `empty object returns failure`() {
            val json = """{}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Neither success nor failure field provided", result.failure)
        }

        @Test
        fun `object with unknown fields only returns failure`() {
            val json = """{"other": "value", "another": 123}"""
            val result = deserialize<String>(json)

            assertNull(result.success)
            assertEquals("Neither success nor failure field provided", result.failure)
        }
    }

    @Nested
    inner class ToResultTests {

        @Test
        fun `toResult returns success for valid success`() {
            val maybeReturn = MaybeReturn(success = "value", failure = null)
            val result = maybeReturn.toResult()

            assertTrue(result.isSuccess)
            assertEquals("value", result.getOrNull())
        }

        @Test
        fun `toResult returns failure for failure`() {
            val maybeReturn = MaybeReturn<String>(success = null, failure = "error")
            val result = maybeReturn.toResult()

            assertTrue(result.isFailure)
            assertEquals("error", result.exceptionOrNull()?.message)
        }
    }

    @Nested
    inner class FactoryMethodTests {

        @Test
        fun `noOutput creates failure with standard message`() {
            val result = MaybeReturn.noOutput<String>()

            assertNull(result.success)
            assertEquals("No output available", result.failure)
        }

        @Test
        fun `failure creates failure with custom message`() {
            val result = MaybeReturn.failure<String>("Custom error")

            assertNull(result.success)
            assertEquals("Custom error", result.failure)
        }
    }

    private inline fun <reified T> deserialize(json: String): MaybeReturn<T> {
        val typeRef = object : TypeReference<MaybeReturn<T>>() {}
        return objectMapper.readValue(json, typeRef)
    }
}
