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
package com.embabel.agent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the [IoBinding] value class.
 *
 * The IoBinding class represents a binding of "name:Type" pairs used in actions
 * for defining inputs and outputs. These tests verify:
 *
 * - Basic extraction of name and type components from various formats
 * - Constructor overloads with Java Class and Kotlin KClass types
 * - Default naming behavior when only a type is provided
 * - Input validation for blank values
 * - Support for custom types and fully qualified names
 */
class IoBindingTest {

    @Test
    fun `test IoBinding extracts name and type correctly`() {
        // Test with explicit name and type
        val binding1 = IoBinding("person:java.lang.String")
        assertEquals("person", binding1.name)
        assertEquals("java.lang.String", binding1.type)

        // Test with simple type string (no explicit name)
        val binding2 = IoBinding("Integer")
        assertEquals(IoBinding.DEFAULT_BINDING, binding2.name)
        assertEquals("Integer", binding2.type)
    }

    @Test
    fun `test IoBinding constructor with name and type string`() {
        val binding = IoBinding("document", "java.lang.String")
        assertEquals("document", binding.name)
        assertEquals("java.lang.String", binding.type)
    }

    @Test
    fun `test IoBinding constructor with Java Class`() {
        // With explicit name
        val binding1 = IoBinding("document", String::class.java)
        assertEquals("document", binding1.name)
        assertEquals("java.lang.String", binding1.type)

        // With default name
        val binding2 = IoBinding(type = Integer::class.java)
        assertEquals(IoBinding.DEFAULT_BINDING, binding2.name)
        assertEquals("java.lang.Integer", binding2.type)
    }

    @Test
    fun `test IoBinding constructor with Kotlin KClass`() {
        // With explicit name
        val binding1 = IoBinding("document", String::class)
        assertEquals("document", binding1.name)
        assertEquals("java.lang.String", binding1.type)

        // With default name
        val binding2 = IoBinding(type = Int::class)
        assertEquals(IoBinding.DEFAULT_BINDING, binding2.name)
        assertEquals("int", binding2.type)
    }

    @Test
    fun `test IoBinding rejects blank values`() {
        assertThrows<IllegalArgumentException> {
            IoBinding("")
        }

        assertThrows<IllegalArgumentException> {
            IoBinding(" ")
        }
    }

    @Test
    fun `test DEFAULT_BINDING constant is 'it'`() {
        assertEquals(IoBinding.DEFAULT_BINDING, IoBinding.DEFAULT_BINDING)
    }

    // Test with a custom class to ensure full qualified name handling
    private class CustomTestClass

    @Test
    fun `test IoBinding with custom class types`() {
        val binding = IoBinding("myObject", CustomTestClass::class)

        assertEquals("myObject", binding.name)
        assertTrue(binding.type.endsWith("CustomTestClass"))
    }
}
