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

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VictoolsSchemaGeneratorTest {

    private val objectMapper = jacksonObjectMapper()

    data class SimpleInput(val name: String, val age: Int)

    data class NullableInput(val required: String, val optional: String?)

    data class AnnotatedInput(
        @field:JsonPropertyDescription("The user's full name")
        val name: String,
        val count: Int,
    )

    data class ListInput(val tags: List<String>, val scores: List<Double>)

    data class ValidatedInput(
        @field:Size(min = 1, max = 100)
        val name: String,
        @field:Min(0) @field:Max(150)
        val age: Int,
        @field:NotNull
        val required: String,
    )

    @Nested
    inner class GenerateSchemaForTypeTest {

        @Test
        fun `string type produces string schema`() {
            val schema = VictoolsSchemaGenerator.generateSchemaForType(String::class.java)
            assertEquals("string", schema.get("type").asText())
        }

        @Test
        fun `int type produces integer schema`() {
            val schema = VictoolsSchemaGenerator.generateSchemaForType(Int::class.java)
            assertEquals("integer", schema.get("type").asText())
        }

        @Test
        fun `List of String produces array schema with string items`() {
            val listOfStringType = object : com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}.type
            val schema = VictoolsSchemaGenerator.generateSchemaForType(listOfStringType)
            assertEquals("array", schema.get("type").asText())
            assertEquals("string", schema.get("items").get("type").asText())
        }
    }

    @Nested
    inner class GenerateToolInputSchemaTest {

        @Test
        fun `produces valid object schema`() {
            val params = listOf(
                VictoolsSchemaGenerator.ParameterInfo("name", String::class.java, "the name", true),
                VictoolsSchemaGenerator.ParameterInfo("age", Int::class.java, "the age", false),
            )
            val json = objectMapper.readTree(VictoolsSchemaGenerator.generateToolInputSchema(params))
            assertEquals("object", json.get("type").asText())
            assertTrue(json.get("properties").has("name"))
            assertTrue(json.get("properties").has("age"))
        }

        @Test
        fun `required array contains only required params`() {
            val params = listOf(
                VictoolsSchemaGenerator.ParameterInfo("a", String::class.java, "a", true),
                VictoolsSchemaGenerator.ParameterInfo("b", String::class.java, "b", false),
            )
            val json = objectMapper.readTree(VictoolsSchemaGenerator.generateToolInputSchema(params))
            val required = json.get("required").map { it.asText() }
            assertTrue(required.contains("a"))
            assertFalse(required.contains("b"))
        }

        @Test
        fun `description is included in property schema`() {
            val params = listOf(
                VictoolsSchemaGenerator.ParameterInfo("x", String::class.java, "my description", true),
            )
            val json = objectMapper.readTree(VictoolsSchemaGenerator.generateToolInputSchema(params))
            assertEquals("my description", json.get("properties").get("x").get("description").asText())
        }
    }

    @Nested
    inner class GenerateClassInputSchemaTest {

        @Test
        fun `produces object schema with all properties`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(SimpleInput::class.java, setOf("name", "age"))
            )
            assertEquals("object", json.get("type").asText())
            assertTrue(json.get("properties").has("name"))
            assertTrue(json.get("properties").has("age"))
        }

        @Test
        fun `required array reflects provided required fields`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(NullableInput::class.java, setOf("required"))
            )
            val required = json.get("required").map { it.asText() }
            assertEquals(listOf("required"), required)
        }

        @Test
        fun `no required array when requiredFields is empty`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(SimpleInput::class.java, emptySet())
            )
            assertNull(json.get("required"))
        }

        @Test
        fun `picks up JsonPropertyDescription from annotated field`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(AnnotatedInput::class.java, setOf("name", "count"))
            )
            assertEquals("The user's full name", json.get("properties").get("name").get("description").asText())
        }

        @Test
        fun `List properties have array schema with items`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(ListInput::class.java, setOf("tags", "scores"))
            )
            assertEquals("array", json.get("properties").get("tags").get("type").asText())
            assertEquals("string", json.get("properties").get("tags").get("items").get("type").asText())
        }
    }

    @Nested
    inner class JakartaValidationTest {

        @Test
        fun `@Size adds minLength and maxLength to string property`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(ValidatedInput::class.java, emptySet())
            )
            val nameProp = json.get("properties").get("name")
            assertEquals(1, nameProp.get("minLength").asInt())
            assertEquals(100, nameProp.get("maxLength").asInt())
        }

        @Test
        fun `@Min and @Max add minimum and maximum to numeric property`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(ValidatedInput::class.java, emptySet())
            )
            val ageProp = json.get("properties").get("age")
            assertEquals(0, ageProp.get("minimum").asInt())
            assertEquals(150, ageProp.get("maximum").asInt())
        }

        @Test
        fun `@NotNull marks field as required via Jakarta module`() {
            val json = objectMapper.readTree(
                VictoolsSchemaGenerator.generateClassInputSchema(ValidatedInput::class.java, setOf("required"))
            )
            val required = json.get("required").map { it.asText() }
            assertTrue(required.contains("required"))
        }
    }
}
