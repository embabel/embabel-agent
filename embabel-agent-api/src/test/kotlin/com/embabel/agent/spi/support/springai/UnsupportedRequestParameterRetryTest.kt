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
package com.embabel.agent.spi.support.springai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.model.tool.ToolCallingChatOptions

class UnsupportedRequestParameterRetryTest {

    @Nested
    inner class ExtractUnsupportedParameter {

        @Test
        fun `parses structured OpenAI JSON error param from Spring AI message`() {
            val message =
                """400 - { "error": { "message": "Unsupported value: 'temperature' does not support 0.8 with this model. Only the default (1) value is supported.", "type": "invalid_request_error", "param": "temperature", "code": "unsupported_value" } }"""
            val param = UnsupportedRequestParameterRetry.extractUnsupportedParameter(
                RuntimeException(message)
            )
            assertThat(param).isEqualTo("temperature")
        }

        @Test
        fun `fails closed on prose-only body without structured error param`() {
            // Fail-closed *contract* test (intentionally kept, not leftover unstructured
            // matching): we do NOT match English prose. Only structured error.param
            // drives a strip/retry.
            val message =
                "400 Unsupported value: 'temperature' does not support 0.8 with this model. Only the default (1) value is supported."
            val param = UnsupportedRequestParameterRetry.extractUnsupportedParameter(
                RuntimeException(message)
            )
            assertThat(param).isNull()
        }

        @Test
        fun `walks cause chain`() {
            val root = RuntimeException(
                """400 - {"error":{"param":"top_p","code":"unsupported_value","message":"..."}}"""
            )
            val wrapped = RuntimeException("call failed", root)
            assertThat(UnsupportedRequestParameterRetry.extractUnsupportedParameter(wrapped))
                .isEqualTo("top_p")
        }

        @Test
        fun `returns null for unrelated errors`() {
            val param = UnsupportedRequestParameterRetry.extractUnsupportedParameter(
                RuntimeException("rate limit exceeded")
            )
            assertThat(param).isNull()
        }

        @Test
        fun `returns null for structured body with non-retryable code`() {
            val message =
                """400 - {"error":{"param":"messages","code":"context_length_exceeded","message":"too long"}}"""
            assertThat(
                UnsupportedRequestParameterRetry.extractUnsupportedParameter(RuntimeException(message))
            ).isNull()
        }
    }

    @Nested
    inner class StripParameter {

        @Test
        fun `clears temperature while keeping other options`() {
            val options = ToolCallingChatOptions.builder()
                .temperature(0.8)
                .topP(0.9)
                .maxTokens(100)
                .build()

            val stripped = UnsupportedRequestParameterRetry.stripParameter(options, "temperature")

            assertThat(stripped).isNotNull
            // Use property names: Spring AI @NullMarked getters NPE in Kotlin when value is null.
            assertThat(stripped).extracting("temperature").isNull()
            assertThat(stripped).extracting("topP").isEqualTo(0.9)
            assertThat(stripped).extracting("maxTokens").isEqualTo(100)
        }

        @Test
        fun `clears top_p by snake_case name`() {
            val options = ToolCallingChatOptions.builder().topP(0.5).temperature(0.2).build()
            val stripped = UnsupportedRequestParameterRetry.stripParameter(options, "top_p")
            assertThat(stripped).extracting("topP").isNull()
            assertThat(stripped).extracting("temperature").isEqualTo(0.2)
        }

        @Test
        fun `returns null for unknown parameter`() {
            val options = ToolCallingChatOptions.builder().temperature(0.5).build()
            assertThat(UnsupportedRequestParameterRetry.stripParameter(options, "seed")).isNull()
        }
    }
}
