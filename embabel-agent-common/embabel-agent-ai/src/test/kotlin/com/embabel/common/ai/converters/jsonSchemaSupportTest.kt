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

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Optional

class JsonSchemaSupportTest {

    private val objectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private data class WithKotlinNullable(
        val required: String,
        val optionalString: String? = null,
        val optionalInt: Int? = null,
        val optionalDouble: Double? = null,
        val optionalBoolean: Boolean? = null,
    )

    private data class WithJavaOptional(
        @JsonProperty(required = true) val optionalString: Optional<String>,
        @JsonProperty(required = true) val optionalInt: Optional<Int>,
        @JsonProperty(required = true) val optionalDouble: Optional<Double>,
        @JsonProperty(required = true) val optionalBoolean: Optional<Boolean>,
        val required: String,
    )

    @Suppress("UNCHECKED_CAST")
    private fun schemaPropertyNode(clazz: Class<*>, field: String) =
        parseJsonSchema(JacksonOutputConverter(clazz as Class<Any>, objectMapper).getJsonSchema())!!
            .path("properties").path(field)

    @Test
    fun `parses JSON schema text`() {
        val schema = """{"type":"object"}"""

        assertThat(parseJsonSchema(schema)).isNotNull()
    }

    @Test
    fun `extracts required field names`() {
        val schema = parseJsonSchema(
            """
                {
                  "type": "object",
                  "required": ["name", "temperature"]
                }
            """.trimIndent()
        )!!

        assertThat(schema.requiredFieldNames()).containsExactlyInAnyOrder("name", "temperature")
    }

    @Nested
    inner class SchemaTypeTests {

        @Test
        fun `returns null for union type array node`() {
            val schema = parseJsonSchema("""{"type": ["string", "null"]}""")!!
            assertThat(schema.schemaType()).isNull()
        }

        @Test
        fun `returns string for scalar type node`() {
            val schema = parseJsonSchema("""{"type": "string"}""")!!
            assertThat(schema.schemaType()).isEqualTo("string")
        }
    }

    @Nested
    inner class KotlinNullableSchemaType {

        // victools JacksonModule reflects Kotlin nullability via required[] only,
        // not as union types — nullable fields still produce plain scalar type nodes

        @Test
        fun `nullable String produces scalar type node - nullability via required array only`() {
            assertThat(schemaPropertyNode(WithKotlinNullable::class.java, "optionalString").schemaType()).isEqualTo("string")
        }

        @Test
        fun `nullable Int produces scalar type node`() {
            assertThat(schemaPropertyNode(WithKotlinNullable::class.java, "optionalInt").schemaType()).isEqualTo("integer")
        }

        @Test
        fun `nullable Double produces scalar type node`() {
            assertThat(schemaPropertyNode(WithKotlinNullable::class.java, "optionalDouble").schemaType()).isEqualTo("number")
        }

        @Test
        fun `nullable Boolean produces scalar type node`() {
            assertThat(schemaPropertyNode(WithKotlinNullable::class.java, "optionalBoolean").schemaType()).isEqualTo("boolean")
        }

        @Test
        fun `non-null String produces scalar type`() {
            assertThat(schemaPropertyNode(WithKotlinNullable::class.java, "required").schemaType()).isEqualTo("string")
        }
    }

    @Nested
    inner class JavaOptionalSchemaType {

        @Test
        fun `Optional String produces union type - schemaType returns null`() {
            assertThat(schemaPropertyNode(WithJavaOptional::class.java, "optionalString").schemaType()).isNull()
        }

        @Test
        fun `Optional Integer produces union type - schemaType returns null`() {
            assertThat(schemaPropertyNode(WithJavaOptional::class.java, "optionalInt").schemaType()).isNull()
        }

        @Test
        fun `Optional Double produces union type - schemaType returns null`() {
            assertThat(schemaPropertyNode(WithJavaOptional::class.java, "optionalDouble").schemaType()).isNull()
        }

        @Test
        fun `Optional Boolean produces union type - schemaType returns null`() {
            assertThat(schemaPropertyNode(WithJavaOptional::class.java, "optionalBoolean").schemaType()).isNull()
        }

        @Test
        fun `non-optional String produces scalar type`() {
            assertThat(schemaPropertyNode(WithJavaOptional::class.java, "required").schemaType()).isEqualTo("string")
        }
    }

    @Test
    fun `detects unsupported json schema keywords`() {
        val schema = parseJsonSchema(
            """
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "${'$'}ref": "#/definitions/Name"
                    }
                  }
                }
            """.trimIndent()
        )!!

        assertThat(schema.hasUnsupportedJsonSchemaKeywords()).isTrue()
    }
}
