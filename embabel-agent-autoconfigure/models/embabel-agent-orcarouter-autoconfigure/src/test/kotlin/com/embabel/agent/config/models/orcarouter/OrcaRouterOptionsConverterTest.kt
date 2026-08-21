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
package com.embabel.agent.config.models.orcarouter

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrcaRouterOptionsConverterTest : OptionsConverterTestSupport(
    optionsConverter = OrcaRouterOptionsConverter
) {
    @Test
    fun `should set override maxTokens default`() {
        val options = optionsConverter.convertOptions(LlmOptions().withMaxTokens(200), "test-model")
        assertEquals(200, options.maxTokens)
    }

    @Test
    fun `should preserve temperature`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTemperature(0.7), "test-model")
        assertEquals(0.7, options.temperature)
    }

    @Test
    fun `should preserve topP`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(0.9), "test-model")
        assertEquals(0.9, options.topP)
    }

    @Test
    fun `should preserve presencePenalty`() {
        val options = optionsConverter.convertOptions(LlmOptions().withPresencePenalty(0.5), "test-model")
        assertEquals(0.5, options.presencePenalty)
    }

    @Test
    fun `should preserve frequencyPenalty`() {
        val options = optionsConverter.convertOptions(LlmOptions().withFrequencyPenalty(0.3), "test-model")
        assertEquals(0.3, options.frequencyPenalty)
    }

    @Test
    fun `should handle null temperature`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "test-model")
        // Spring AI 2.0's OpenAiChatOptions package is @NullMarked, so Kotlin treats
        // getTemperature() as non-null Double — direct property access NPEs on a null
        // runtime value. Read into a Double? local to bypass. (Migration doc §5.11.)
        val temperature: Double? = options.temperature
        assertEquals(null, temperature)
    }

    @Test
    fun `should preserve model id`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "orcarouter/auto")
        assertTrue(options.model.contains("orcarouter/auto"))
    }
}
