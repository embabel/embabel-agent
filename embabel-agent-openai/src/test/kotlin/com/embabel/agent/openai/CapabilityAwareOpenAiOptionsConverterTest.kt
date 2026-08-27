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
package com.embabel.agent.openai

import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatOptions

class CapabilityAwareOpenAiOptionsConverterTest {

    @Nested
    inner class DefaultCapabilities {

        @Test
        fun `includes all sampling parameters and maxTokens`() {
            val options = LlmOptions()
                .withTemperature(0.7)
                .withTopP(0.9)
                .withMaxTokens(1000)
                .withPresencePenalty(0.5)
                .withFrequencyPenalty(0.3)

            val result = (CapabilityAwareOpenAiOptionsConverter().convertOptions(options, "test-model") as OpenAiChatOptions)

            assertEquals(0.7, result.temperature)
            assertEquals(0.9, result.topP)
            assertEquals(1000, result.maxTokens)
            val maxCompletionTokens: Int? = result.maxCompletionTokens
            assertNull(maxCompletionTokens)
            assertEquals(0.5, result.presencePenalty)
            assertEquals(0.3, result.frequencyPenalty)
        }
    }

    @Nested
    inner class TemperatureRestricted {

        private val converter = CapabilityAwareOpenAiOptionsConverter(ModelCapabilities.WITHOUT_TEMPERATURE)

        @Test
        fun `drops non-default temperature rather than sending it`() {
            val options = LlmOptions().withTemperature(0.8)
            val result = (converter.convertOptions(options, "gpt-4.1") as OpenAiChatOptions)
            val temperature: Double? = result.temperature
            assertNull(temperature, "Non-default temperature must be omitted, not sent")
        }

        @Test
        fun `omits default temperature without error`() {
            val options = LlmOptions().withTemperature(1.0)
            val result = (converter.convertOptions(options, "test-model") as OpenAiChatOptions)
            val temperature: Double? = result.temperature
            assertNull(temperature)
        }

        @Test
        fun `omits null temperature without error`() {
            val options = LlmOptions().withTopP(0.9).withMaxTokens(500)
            val result = (converter.convertOptions(options, "test-model") as OpenAiChatOptions)
            val temperature: Double? = result.temperature
            assertNull(temperature)
            assertEquals(0.9, result.topP)
            assertEquals(500, result.maxTokens)
        }

        @Test
        fun `preserves other parameters when temperature omitted`() {
            val options = LlmOptions()
                .withTemperature(0.7)
                .withTopP(0.9)
                .withMaxTokens(500)
                .withPresencePenalty(0.2)
                .withFrequencyPenalty(0.1)

            val result = (converter.convertOptions(options, "test-model") as OpenAiChatOptions)

            val temperature: Double? = result.temperature
            assertNull(temperature)
            assertEquals(0.9, result.topP)
            assertEquals(500, result.maxTokens)
            assertEquals(0.2, result.presencePenalty)
            assertEquals(0.1, result.frequencyPenalty)
        }
    }

    @Nested
    inner class MaxCompletionTokens {

        private val converter = CapabilityAwareOpenAiOptionsConverter(
            ModelCapabilities(supportsTemperature = false, usesMaxCompletionTokens = true)
        )

        @Test
        fun `maps token limit to maxCompletionTokens and leaves maxTokens unset`() {
            val options = LlmOptions().withMaxTokens(500)
            val result = (converter.convertOptions(options, "gpt-5") as OpenAiChatOptions)

            assertEquals(500, result.maxCompletionTokens)
            val maxTokens: Int? = result.maxTokens
            assertNull(maxTokens)
        }

        @Test
        fun `sends neither token field when no limit was asked for`() {
            val options = LlmOptions()
            val result = (converter.convertOptions(options, "gpt-5") as OpenAiChatOptions)

            val maxCompletionTokens: Int? = result.maxCompletionTokens
            val maxTokens: Int? = result.maxTokens
            assertNull(maxCompletionTokens)
            assertNull(maxTokens)
        }
    }

    @Nested
    inner class MultipleRestrictedParameters {

        @Test
        fun `drops all unsupported sampling params and keeps the token limit`() {
            val converter = CapabilityAwareOpenAiOptionsConverter(
                ModelCapabilities(
                    supportsTemperature = false,
                    supportsTopP = false,
                    supportsFrequencyPenalty = false,
                    supportsPresencePenalty = false,
                    usesMaxCompletionTokens = true,
                )
            )
            val options = LlmOptions()
                .withTemperature(0.5)
                .withTopP(0.8)
                .withMaxTokens(200)
                .withFrequencyPenalty(0.4)
                .withPresencePenalty(0.3)

            val result = (converter.convertOptions(options, "restricted-model") as OpenAiChatOptions)

            val temperature: Double? = result.temperature
            val topP: Double? = result.topP
            val frequencyPenalty: Double? = result.frequencyPenalty
            val presencePenalty: Double? = result.presencePenalty
            assertNull(temperature)
            assertNull(topP)
            assertNull(frequencyPenalty)
            assertNull(presencePenalty)
            assertEquals(200, result.maxCompletionTokens)
        }

        @Test
        fun `drops unsupported topP while keeping temperature`() {
            val converter = CapabilityAwareOpenAiOptionsConverter(
                ModelCapabilities(supportsTopP = false)
            )
            val options = LlmOptions().withTemperature(0.5).withTopP(0.8)
            val result = (converter.convertOptions(options, "no-top-p") as OpenAiChatOptions)
            assertEquals(0.5, result.temperature)
            val topP: Double? = result.topP
            assertNull(topP)
        }

        @Test
        fun `omits all unsupported when values are null or default temperature`() {
            val converter = CapabilityAwareOpenAiOptionsConverter(
                ModelCapabilities(
                    supportsTemperature = false,
                    supportsTopP = false,
                    supportsFrequencyPenalty = false,
                    supportsPresencePenalty = false,
                    usesMaxCompletionTokens = true,
                )
            )
            val options = LlmOptions().withTemperature(1.0).withMaxTokens(200)
            val result = (converter.convertOptions(options, "test-model") as OpenAiChatOptions)

            val temperature: Double? = result.temperature
            val topP: Double? = result.topP
            val frequencyPenalty: Double? = result.frequencyPenalty
            val presencePenalty: Double? = result.presencePenalty
            assertNull(temperature)
            assertNull(topP)
            assertNull(frequencyPenalty)
            assertNull(presencePenalty)
            assertEquals(200, result.maxCompletionTokens)
            val maxTokens: Int? = result.maxTokens
            assertNull(maxTokens)
        }
    }

    @Nested
    inner class BackwardCompatibleAliases {

        @Test
        fun `Gpt5ChatOptionsConverter drops non-default temperature`() {
            val options = LlmOptions().withTemperature(0.5)
            val result = (Gpt5ChatOptionsConverter.convertOptions(options, "test-model") as OpenAiChatOptions)
            val temperature: Double? = result.temperature
            assertNull(temperature)
        }

        @Test
        fun `Gpt5ChatOptionsConverter drops topP when set`() {
            val options = LlmOptions().withTopP(0.9)
            val result = (Gpt5ChatOptionsConverter.convertOptions(options, "test-model") as OpenAiChatOptions)
            val topP: Double? = result.topP
            assertNull(topP)
        }

        @Test
        fun `Gpt5ChatOptionsConverter omits defaults and uses maxCompletionTokens`() {
            val options = LlmOptions().withTemperature(1.0).withMaxTokens(300)
            val result = (Gpt5ChatOptionsConverter.convertOptions(options, "test-model") as OpenAiChatOptions)
            val temperature: Double? = result.temperature
            assertNull(temperature)
            assertEquals(300, result.maxCompletionTokens)
            val maxTokens: Int? = result.maxTokens
            assertNull(maxTokens)
        }

        @Test
        fun `StandardOpenAiOptionsConverter includes temperature and maxTokens`() {
            val options = LlmOptions().withTemperature(0.5).withMaxTokens(100)
            val result = (StandardOpenAiOptionsConverter.convertOptions(options, "test-model") as OpenAiChatOptions)
            assertEquals(0.5, result.temperature)
            assertEquals(100, result.maxTokens)
        }
    }
}
