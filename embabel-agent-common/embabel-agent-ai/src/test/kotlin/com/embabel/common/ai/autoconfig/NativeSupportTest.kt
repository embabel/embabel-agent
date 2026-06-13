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
package com.embabel.common.ai.autoconfig

import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class NativeSupportTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class NativeSupportDefaultsTests {

        @Test
        fun `native support defaults to no structured output`() {
            val nativeSupport = NativeSupport()

            assertNull(nativeSupport.structuredOutput)
        }

        @Test
        fun `native capability defaults to unset fields`() {
            val capability = NativeStructuredOutputCapability()

            assertNull(capability.supported)
            assertNull(capability.strategy)
            assertNull(capability.strict)
            assertNull(capability.promptInstructions)
            assertTrue(capability.options.isEmpty())
        }

        @Test
        fun `llm auto config metadata defaults to null native support`() {
            val metadata = object : LlmAutoConfigMetadata {
                override val name: String = "test"
                override val modelId: String = "test-model"
                override val displayName: String? = null
                override val knowledgeCutoffDate: LocalDate? = null
                override val pricingModel: com.embabel.common.ai.model.PerTokenPricingModel? = null
            }

            assertNull(metadata.nativeSupport)
        }
    }

    @Nested
    inner class NativeSupportAliasTests {

        @Test
        fun `native support aliases deserialize through jackson`() {
            val json = """
                {
                  "structured-output": {
                    "supported": true,
                    "strategy": "tool",
                    "strict": true,
                    "prompt-instructions": "include",
                    "options": {
                      "input_schema_field": "input_schema"
                    }
                  }
                }
            """.trimIndent()

            val nativeSupport = objectMapper.readValue(json, NativeSupport::class.java)
            val structuredOutput = nativeSupport.structuredOutput

            assertNotNull(structuredOutput)
            assertTrue(structuredOutput?.supported == true)
            assertEquals("tool", structuredOutput?.strategy)
            assertEquals(true, structuredOutput?.strict)
            assertEquals("include", structuredOutput?.promptInstructions)
            assertEquals("input_schema", structuredOutput?.options?.get("input_schema_field"))
        }

        @Test
        fun `native support merges defaults with overrides`() {
            val defaults = NativeSupport(
                structuredOutput = NativeStructuredOutputCapability(
                    supported = true,
                    strategy = "response_format",
                    strict = true,
                    promptInstructions = "include",
                    options = mapOf("response_format_field" to "response_format"),
                )
            )
            val override = NativeSupport(
                structuredOutput = NativeStructuredOutputCapability(
                    strict = false,
                    options = mapOf("json_schema_field" to "json_schema"),
                )
            )

            val merged = override.merge(defaults)
            val structuredOutput = merged?.structuredOutput

            assertNotNull(structuredOutput)
            assertEquals(true, structuredOutput?.supported)
            assertEquals("response_format", structuredOutput?.strategy)
            assertEquals(false, structuredOutput?.strict)
            assertEquals("include", structuredOutput?.promptInstructions)
            assertEquals("response_format", structuredOutput?.options?.get("response_format_field"))
            assertEquals("json_schema", structuredOutput?.options?.get("json_schema_field"))
        }
    }
}
