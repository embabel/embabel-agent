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
package com.embabel.agent.config.models.dashscope

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatOptions

// Calls the deprecated 1-arg convertOptions() directly to verify field mapping in isolation.
// Model stamping is not tested here — it is covered by OptionsConverter.convertOptions(options, model).
class DashScopeOptionsConverterTest : OptionsConverterTestSupport<OpenAiChatOptions>(
    optionsConverter = DashScopeOptionsConverter
) {
    @Test
    fun `should set override maxTokens default`() {
        val options = optionsConverter.convertOptions(LlmOptions().withMaxTokens(200))
        assertEquals(200, options.maxTokens)
    }

    @Test
    fun `should clamp temperature below minimum to minimum`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTemperature(-0.5))
        val temperature = requireNotNull(options.temperature) { "Temperature should not be null after clamping" }
        assertTrue(temperature >= 0.0, "Temperature should be clamped to at least 0.0")
    }

    @Test
    fun `should clamp temperature at exactly 2_0 to 1_99`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTemperature(2.0))
        assertEquals(1.99, options.temperature)
    }

    @Test
    fun `should clamp temperature above maximum to 1_99`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTemperature(3.0))
        assertEquals(1.99, options.temperature)
    }

    @Test
    fun `should preserve valid temperature`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTemperature(0.7))
        assertEquals(0.7, options.temperature)
    }

    @Test
    fun `should handle null temperature`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertNull(options.temperature)
    }

    @Test
    fun `should clamp topP at zero to 0_01`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(0.0))
        assertEquals(0.01, options.topP)
    }

    @Test
    fun `should clamp topP below zero to 0_01`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(-0.5))
        assertEquals(0.01, options.topP)
    }

    @Test
    fun `should clamp topP above 1_0 to 1_0`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(1.5))
        assertEquals(1.0, options.topP)
    }

    @Test
    fun `should preserve valid topP`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(0.9))
        assertEquals(0.9, options.topP)
    }

    @Test
    fun `should preserve topP at exactly 1_0`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(1.0))
        assertEquals(1.0, options.topP)
    }

    @Test
    fun `should handle null topP`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertNull(options.topP)
    }
}
