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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.common.ai.autoconfig.NativeStructuredOutputCapability
import com.embabel.common.ai.autoconfig.NativeSupport
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.prompt.ChatOptions

class AnthropicNativeStructuredOutputConfigurerTest {

    @Nested
    inner class MutationTests {

        @Test
        fun `configures output schema for supported native structured output`() {
            val options = AnthropicChatOptions.builder()
                .model("claude-sonnet-4-6")
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
                    strategy = "tool",
                    strict = true,
                )
            )

            val configured = AnthropicNativeStructuredOutputConfigurer.configure(
                options = options,
                structuredOutput = request,
                nativeSupport = nativeSupport,
                llm = null,
            )

            assertThat(configured).isInstanceOf(AnthropicChatOptions::class.java)
            val configuredOptions = configured as AnthropicChatOptions
            assertThat(configuredOptions.outputSchema).isNotNull
        }
    }

    @Nested
    inner class NoOpTests {

        @Test
        fun `returns original options when given a non anthropic chat options instance`() {
            val options = mockk<ChatOptions>(relaxed = true)

            val configured = AnthropicNativeStructuredOutputConfigurer.configure(
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

        @Test
        fun `returns original options when native structured output strategy does not match anthropic`() {
            val options = AnthropicChatOptions.builder()
                .model("claude-sonnet-4-6")
                .build()

            val configured = AnthropicNativeStructuredOutputConfigurer.configure(
                options = options,
                structuredOutput = StructuredOutputRequest(name = "Answer", schema = "{}"),
                nativeSupport = NativeSupport(
                    structuredOutput = NativeStructuredOutputCapability(
                        supported = true,
                        strategy = "response_format",
                    )
                ),
                llm = null,
            )

            assertThat(configured).isSameAs(options)
        }
    }
}
