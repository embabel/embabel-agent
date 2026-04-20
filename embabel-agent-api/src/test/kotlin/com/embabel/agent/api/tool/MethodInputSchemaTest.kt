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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MethodInputSchemaTest {

    // Sample methods used as fixtures — compiled with -parameters so names are preserved
    @Suppress("unused")
    private fun noParams(): String = ""

    @Suppress("unused")
    private fun oneParam(name: String): String = name

    @Suppress("unused")
    private fun multipleParams(id: Long, active: Boolean, score: Double): String = ""

    @Suppress("unused")
    private fun listParam(tags: List<String>): String = ""

    private fun method(name: String) =
        this::class.java.getDeclaredMethod(name, *when (name) {
            "noParams" -> emptyArray()
            "oneParam" -> arrayOf(String::class.java)
            "multipleParams" -> arrayOf(Long::class.java, Boolean::class.java, Double::class.java)
            "listParam" -> arrayOf(List::class.java)
            else -> throw IllegalArgumentException("Unknown fixture method: $name")
        })

    @Nested
    inner class Parameters {

        @Test
        fun `no parameters produces empty parameter list`() {
            val schema = MethodInputSchema.fromMethod(method("noParams"))
            assertTrue(schema.parameters.isEmpty())
        }

        @Test
        fun `single parameter is reflected`() {
            val schema = MethodInputSchema.fromMethod(method("oneParam"))
            assertEquals(1, schema.parameters.size)
            val param = schema.parameters.single()
            assertEquals("name", param.name)
            assertEquals(Tool.ParameterType.STRING, param.type)
            assertTrue(param.required)
        }

        @Test
        fun `multiple parameters are all present and in order`() {
            val schema = MethodInputSchema.fromMethod(method("multipleParams"))
            assertEquals(3, schema.parameters.size)
            assertEquals(listOf("id", "active", "score"), schema.parameters.map { it.name })
        }

        @Test
        fun `all parameters are required`() {
            val schema = MethodInputSchema.fromMethod(method("multipleParams"))
            assertTrue(schema.parameters.all { it.required })
        }

        @Test
        fun `list parameter maps to ARRAY type`() {
            val schema = MethodInputSchema.fromMethod(method("listParam"))
            assertEquals(Tool.ParameterType.ARRAY, schema.parameters.single().type)
        }
    }

    @Nested
    inner class JsonSchema {

        @Test
        fun `no-parameter method produces schema with empty properties`() {
            val json = MethodInputSchema.fromMethod(method("noParams")).toJsonSchema()
            assertTrue(json.contains("\"properties\":{}"), "Expected empty properties in: $json")
        }

        @Test
        fun `single parameter appears in JSON schema`() {
            val json = MethodInputSchema.fromMethod(method("oneParam")).toJsonSchema()
            assertTrue(json.contains("\"name\""), "Expected 'name' property in: $json")
        }

        @Test
        fun `required array contains all parameter names`() {
            val json = MethodInputSchema.fromMethod(method("multipleParams")).toJsonSchema()
            assertTrue(json.contains("\"id\""), "Expected 'id' in: $json")
            assertTrue(json.contains("\"active\""), "Expected 'active' in: $json")
            assertTrue(json.contains("\"score\""), "Expected 'score' in: $json")
        }
    }
}
