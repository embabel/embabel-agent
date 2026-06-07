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
package com.embabel.agent.spi.support.nativeoutput

import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.NativeStructuredOutputRequest
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.chat.UserMessage
import com.embabel.common.ai.autoconfig.NativeStructuredOutputCapability
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.model.NativeStructuredOutputMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NativeStructuredOutputSupportTest {

    @Nested
    inner class PolicyTests {

        @Test
        fun `uses native structured output for compatible flat objects`() {
            val nativeSupport = nativeSupport(true)
            val request = nativeRequest(
                """
                    {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" },
                        "temperature": { "type": "integer" }
                      },
                      "required": ["name", "temperature"],
                      "additionalProperties": false
                    }
                """.trimIndent()
            )

            assertThat(nativeSupport.shouldUseNativeStructuredOutput(request)).isTrue()
        }

        @Test
        fun `disables native structured output when mode is disabled`() {
            val nativeSupport = nativeSupport(true)
            val request = nativeRequest(
                """
                    {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" }
                      },
                      "required": ["name"],
                      "additionalProperties": false
                    }
                """.trimIndent(),
                mode = NativeStructuredOutputMode.DISABLED,
            )

            assertThat(nativeSupport.shouldUseNativeStructuredOutput(request)).isFalse()
        }

        @Test
        fun `disables native structured output when capability is off`() {
            val nativeSupport = nativeSupport(false)
            val request = nativeRequest(
                """
                    {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" }
                      },
                      "required": ["name"],
                      "additionalProperties": false
                    }
                """.trimIndent()
            )

            assertThat(nativeSupport.shouldUseNativeStructuredOutput(request)).isFalse()
        }

        @Test
        fun `rejects arrays of objects for native structured output`() {
            val nativeSupport = nativeSupport(true)
            val request = nativeRequest(
                """
                    {
                      "type": "object",
                      "properties": {
                        "items": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "properties": {
                              "name": { "type": "string" }
                            },
                            "required": ["name"]
                          }
                        }
                      },
                      "required": ["items"],
                      "additionalProperties": false
                    }
                """.trimIndent()
            )

            assertThat(nativeSupport.shouldUseNativeStructuredOutput(request)).isFalse()
        }
    }

    private fun nativeSupport(supported: Boolean): NativeSupport =
        NativeSupport(
            structuredOutput = NativeStructuredOutputCapability(
                supported = supported,
            )
        )

    private fun nativeRequest(
        schema: String,
        mode: NativeStructuredOutputMode = NativeStructuredOutputMode.DEFAULT,
    ): LlmMessageRequest =
        LlmMessageRequest(
            messages = listOf(UserMessage("prompt")),
            tools = emptyList(),
            nativeStructuredOutputRequest = NativeStructuredOutputRequest(
                structuredOutputRequest = StructuredOutputRequest(
                    name = "Answer",
                    schema = schema,
                ),
                nativeStructuredOutputMode = mode,
            ),
        )
}
