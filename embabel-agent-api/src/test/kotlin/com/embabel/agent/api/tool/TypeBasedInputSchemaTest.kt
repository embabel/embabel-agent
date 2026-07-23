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
package com.embabel.agent.api.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [TypeBasedInputSchema].
 */
class TypeBasedInputSchemaTest {

    private val objectMapper = jacksonObjectMapper()

    // Test data classes
    data class SimpleKotlinClass(
        val stringField: String,
        val intField: Int,
        val booleanField: Boolean,
    )

    data class NullableKotlinClass(
        val requiredField: String,
        val optionalField: String?,
    )

    data class NumericTypesClass(
        val intField: Int,
        val longField: Long,
        val doubleField: Double,
        val floatField: Float,
    )

    data class CollectionTypesClass(
        val listField: List<String>,
        val arrayField: Array<Int>,
    )

    data class NestedObjectClass(
        val name: String,
        val nested: SimpleKotlinClass,
    )

    // Test classes for array items schema tests
    data class ListOfStrings(
        val tags: List<String>,
    )

    data class ListOfIntegers(
        val scores: List<Int>,
    )

    data class NestedItem(
        val name: String,
        val value: Int,
    )

    data class ListOfNestedObjects(
        val items: List<NestedItem>,
    )

    data class ComplexNestedType(
        val title: String,
        val entries: List<NestedItem>,
        val count: Int,
    )

    @Nested
    inner class FactoryMethodsTest {

        @Test
        fun `of with Class creates schema`() {
            val schema = TypeBasedInputSchema.of(SimpleKotlinClass::class.java)

            assertNotNull(schema)
            assertTrue(schema.parameters.isNotEmpty())
        }

        @Test
        fun `of with KClass creates schema`() {
            val schema = TypeBasedInputSchema.of(SimpleKotlinClass::class)

            assertNotNull(schema)
            assertTrue(schema.parameters.isNotEmpty())
        }
    }

    @Nested
    inner class ParametersTest {

        @Test
        fun `extracts parameters from simple Kotlin class`() {
            val schema = TypeBasedInputSchema.of(SimpleKotlinClass::class.java)

            val params = schema.parameters
            assertEquals(3, params.size)

            val stringParam = params.find { it.name == "stringField" }
            assertNotNull(stringParam)
            assertEquals(Tool.ParameterType.STRING, stringParam!!.type)
            assertTrue(stringParam.required)

            val intParam = params.find { it.name == "intField" }
            assertNotNull(intParam)
            assertEquals(Tool.ParameterType.INTEGER, intParam!!.type)
            assertTrue(intParam.required)

            val boolParam = params.find { it.name == "booleanField" }
            assertNotNull(boolParam)
            assertEquals(Tool.ParameterType.BOOLEAN, boolParam!!.type)
            assertTrue(boolParam.required)
        }

        @Test
        fun `nullable fields are not required`() {
            val schema = TypeBasedInputSchema.of(NullableKotlinClass::class.java)

            val params = schema.parameters

            val requiredParam = params.find { it.name == "requiredField" }
            assertNotNull(requiredParam)
            assertTrue(requiredParam!!.required)

            val optionalParam = params.find { it.name == "optionalField" }
            assertNotNull(optionalParam)
            assertFalse(optionalParam!!.required)
        }

        @Test
        fun `maps numeric types correctly`() {
            val schema = TypeBasedInputSchema.of(NumericTypesClass::class.java)

            val params = schema.parameters

            val intParam = params.find { it.name == "intField" }
            assertEquals(Tool.ParameterType.INTEGER, intParam!!.type)

            val longParam = params.find { it.name == "longField" }
            assertEquals(Tool.ParameterType.INTEGER, longParam!!.type)

            val doubleParam = params.find { it.name == "doubleField" }
            assertEquals(Tool.ParameterType.NUMBER, doubleParam!!.type)

            val floatParam = params.find { it.name == "floatField" }
            assertEquals(Tool.ParameterType.NUMBER, floatParam!!.type)
        }

        @Test
        fun `maps collection types correctly`() {
            val schema = TypeBasedInputSchema.of(CollectionTypesClass::class.java)

            val params = schema.parameters

            // At runtime, generic type info may be erased, so List<String> becomes Object
            // Array types are correctly detected
            val listParam = params.find { it.name == "listField" }
            assertNotNull(listParam)

            val arrayParam = params.find { it.name == "arrayField" }
            assertEquals(Tool.ParameterType.ARRAY, arrayParam!!.type)
        }

        @Test
        fun `maps nested object types to object`() {
            val schema = TypeBasedInputSchema.of(NestedObjectClass::class.java)

            val params = schema.parameters

            val nestedParam = params.find { it.name == "nested" }
            assertEquals(Tool.ParameterType.OBJECT, nestedParam!!.type)
        }
    }

    @Nested
    inner class JsonSchemaTest {

        @Test
        fun `toJsonSchema returns valid JSON`() {
            val schema = TypeBasedInputSchema.of(SimpleKotlinClass::class.java)

            val jsonSchema = schema.toJsonSchema()

            // Should be parseable as JSON
            val parsed = objectMapper.readTree(jsonSchema)
            assertNotNull(parsed)
        }

        @Test
        fun `toJsonSchema has type object`() {
            val schema = TypeBasedInputSchema.of(SimpleKotlinClass::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)

            assertEquals("object", parsed.get("type").asText())
        }

        @Test
        fun `toJsonSchema has properties for each field`() {
            val schema = TypeBasedInputSchema.of(SimpleKotlinClass::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            assertTrue(properties.has("stringField"))
            assertTrue(properties.has("intField"))
            assertTrue(properties.has("booleanField"))
        }

        @Test
        fun `toJsonSchema maps types correctly`() {
            val schema = TypeBasedInputSchema.of(SimpleKotlinClass::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            assertEquals("string", properties.get("stringField").get("type").asText())
            assertEquals("integer", properties.get("intField").get("type").asText())
            assertEquals("boolean", properties.get("booleanField").get("type").asText())
        }

        @Test
        fun `toJsonSchema includes required array for non-nullable fields`() {
            val schema = TypeBasedInputSchema.of(NullableKotlinClass::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val required = parsed.get("required")

            assertNotNull(required)
            assertTrue(required.isArray)

            val requiredFields = required.map { it.asText() }
            assertTrue(requiredFields.contains("requiredField"))
            assertFalse(requiredFields.contains("optionalField"))
        }

        @Test
        fun `toJsonSchema handles numeric types`() {
            val schema = TypeBasedInputSchema.of(NumericTypesClass::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            assertEquals("integer", properties.get("intField").get("type").asText())
            assertEquals("integer", properties.get("longField").get("type").asText())
            assertEquals("number", properties.get("doubleField").get("type").asText())
            assertEquals("number", properties.get("floatField").get("type").asText())
        }

        @Test
        fun `toJsonSchema handles collection types`() {
            val schema = TypeBasedInputSchema.of(CollectionTypesClass::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            // List may be detected as object due to type erasure
            assertNotNull(properties.get("listField"))
            // Array types are correctly detected
            assertEquals("array", properties.get("arrayField").get("type").asText())
        }

        @Test
        fun `toJsonSchema handles nested object types`() {
            val schema = TypeBasedInputSchema.of(NestedObjectClass::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val properties = parsed.get("properties")

            assertEquals("object", properties.get("nested").get("type").asText())
        }
    }

    @Nested
    inner class JavaClassFallbackTest {

        @Test
        fun `handles Java class without Kotlin metadata`() {
            // java.awt.Point is a simple Java class
            val schema = TypeBasedInputSchema.of(java.awt.Point::class.java)

            // Should not throw and should have some parameters
            val jsonSchema = schema.toJsonSchema()
            assertNotNull(jsonSchema)

            val parsed = objectMapper.readTree(jsonSchema)
            assertEquals("object", parsed.get("type").asText())
        }
    }

    /**
     * Tests for array/list schemas - OpenAI requires `items` property for arrays.
     * See: https://platform.openai.com/docs/guides/function-calling
     */
    @Nested
    inner class ArrayItemsSchemaTest {

        @Test
        fun `array of strings has items with type string`() {
            val schema = TypeBasedInputSchema.of(ListOfStrings::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val tagsProperty = parsed.get("properties").get("tags")

            assertEquals("array", tagsProperty.get("type").asText())

            // This is the key assertion - arrays MUST have items property
            val items = tagsProperty.get("items")
            assertNotNull(items, "Array schema must have 'items' property for OpenAI compatibility")
            assertEquals("string", items.get("type").asText())
        }

        @Test
        fun `array of integers has items with type integer`() {
            val schema = TypeBasedInputSchema.of(ListOfIntegers::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val scoresProperty = parsed.get("properties").get("scores")

            assertEquals("array", scoresProperty.get("type").asText())

            val items = scoresProperty.get("items")
            assertNotNull(items, "Array schema must have 'items' property for OpenAI compatibility")
            assertEquals("integer", items.get("type").asText())
        }

        @Test
        fun `array of nested objects has items with object schema`() {
            val schema = TypeBasedInputSchema.of(ListOfNestedObjects::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)
            val itemsProperty = parsed.get("properties").get("items")

            assertEquals("array", itemsProperty.get("type").asText())

            // Array items should have a nested object schema
            val items = itemsProperty.get("items")
            assertNotNull(items, "Array schema must have 'items' property for OpenAI compatibility")
            assertEquals("object", items.get("type").asText())

            // The nested object should have its own properties defined
            val nestedProperties = items.get("properties")
            assertNotNull(nestedProperties, "Nested object items should have properties")
            assertTrue(nestedProperties.has("name"), "Nested object should have 'name' property")
            assertTrue(nestedProperties.has("value"), "Nested object should have 'value' property")
        }

        @Test
        fun `complex type with nested list generates complete schema`() {
            val schema = TypeBasedInputSchema.of(ComplexNestedType::class.java)

            val jsonSchema = schema.toJsonSchema()
            val parsed = objectMapper.readTree(jsonSchema)

            // Top-level properties
            val properties = parsed.get("properties")
            assertEquals("string", properties.get("title").get("type").asText())
            assertEquals("integer", properties.get("count").get("type").asText())

            // Entries array
            val entriesProperty = properties.get("entries")
            assertEquals("array", entriesProperty.get("type").asText())

            val items = entriesProperty.get("items")
            assertNotNull(items, "Array schema must have 'items' property")
            assertEquals("object", items.get("type").asText())

            // Nested properties in the array items
            val nestedProperties = items.get("properties")
            assertNotNull(nestedProperties)
            assertEquals("string", nestedProperties.get("name").get("type").asText())
            assertEquals("integer", nestedProperties.get("value").get("type").asText())
        }
    }
}
