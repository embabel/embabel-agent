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
package com.embabel.agent.config.models.openai

import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.common.ai.autoconfig.NativeStructuredOutputCapability
import com.embabel.common.ai.autoconfig.NativeSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.ResponseFormat
import io.mockk.mockk

class OpenAiNativeStructuredOutputConfigurerTest {

    @Nested
    inner class MutationTests {

        @Test
        fun `configures response format for supported native structured output`() {
            val options = OpenAiChatOptions.builder()
                .model("gpt-5.4")
                .build()
            val request = StructuredOutputRequest(
                name = "Answer",
                schema = """
                    {
                      "type": "object",
                      "properties": {
                        "answer": { "type": "string" }
                      },
                      "required": ["answer"]
                    }
                """.trimIndent(),
            )
            val nativeSupport = NativeSupport(
                structuredOutput = NativeStructuredOutputCapability(
                    supported = true,
                    strategy = "response_format",
                    strict = true,
                )
            )

            val configured = OpenAiNativeStructuredOutputConfigurer.configure(
                options = options,
                structuredOutput = request,
                nativeSupport = nativeSupport,
                llm = null,
            )

            assertThat(configured).isInstanceOf(OpenAiChatOptions::class.java)
            val configuredOptions = configured as OpenAiChatOptions
            assertThat(configuredOptions.responseFormat?.type).isEqualTo(ResponseFormat.Type.JSON_SCHEMA)
            assertThat(configuredOptions.responseFormat?.jsonSchema?.name).isEqualTo("custom_schema")
            assertThat(configuredOptions.responseFormat?.jsonSchema?.strict).isTrue()
            assertThat(configuredOptions.responseFormat?.jsonSchema?.schema?.get("type")).isEqualTo("object")
        }
    }

    @Nested
    inner class NoOpTests {

        @Test
        fun `returns original options when native structured output is unsupported`() {
            val options = mockk<ChatOptions>(relaxed = true)

            val configured = OpenAiNativeStructuredOutputConfigurer.configure(
                options = options,
                structuredOutput = StructuredOutputRequest(name = "Answer", schema = "{}"),
                nativeSupport = NativeSupport(
                    structuredOutput = NativeStructuredOutputCapability(
                        supported = false,
                        strategy = "response_format",
                    )
                ),
                llm = null,
            )

            assertThat(configured).isSameAs(options)
        }

        @Test
        fun `returns original options when native structured output strategy does not match openai`() {
            val options = mockk<ChatOptions>(relaxed = true)

            val configured = OpenAiNativeStructuredOutputConfigurer.configure(
                options = options,
                structuredOutput = StructuredOutputRequest(name = "Answer", schema = "{}"),
                nativeSupport = NativeSupport(
                    structuredOutput = NativeStructuredOutputCapability(
                        supported = true,
                        strategy = "tool",
                    )
                ),
                llm = null,
            )

            assertThat(configured).isSameAs(options)
        }
    }
}
