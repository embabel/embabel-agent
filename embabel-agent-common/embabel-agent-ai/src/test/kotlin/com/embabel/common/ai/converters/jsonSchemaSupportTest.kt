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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsonSchemaSupportTest {

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
