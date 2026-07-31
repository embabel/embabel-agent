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

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.openai.Gpt5ChatOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class Gpt5ChatOptionsConverterTest(
) {

    @Test
    fun `ignores temperature`() {
        val llmo = LlmOptions().withTemperature(temperature = 0.5)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        // Spring AI 2.0's OpenAiChatOptions package is @NullMarked, so Kotlin treats
        // getTemperature() as returning non-null Double — direct property access
        // would NPE on a null runtime value. Read into a Double? local to bypass.
        val temperature: Double? = options.temperature
        assertNull(temperature, "Custom temperature should be ignored for GPT-5")
    }

    @Test
    fun `respects non-temperature options`() {
        val llmo = LlmOptions.withModel(OpenAiModels.GPT_5).withTopK(10).withTopP(.2)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertEquals(llmo.topP, options.topP, "Top P should be preserved for GPT-5")
        // Same @NullMarked workaround as in `ignores temperature` above.
        val temperature: Double? = options.temperature
        assertNull(temperature, "Temperature should not be set for GPT-5")
    }

    @Disabled("We not support thinking effort yet")
    @Test
    fun `supports thinking effort`() {

    }

    @Test
    fun `handles temperature equal to 1_0 without warning`() {
        val llmo = LlmOptions().withTemperature(temperature = 1.0)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertNull(options.temperature, "Temperature 1.0 should be ignored silently")
    }

    @Test
    fun `handles null temperature`() {
        val llmo = LlmOptions()
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertNull(options.temperature, "Null temperature should remain null")
    }

    @Test
    fun `preserves maxTokens`() {
        val llmo = LlmOptions().withMaxTokens(500)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertEquals(500, options.maxTokens, "Max tokens should be preserved")
    }

    @Test
    fun `preserves presencePenalty`() {
        val llmo = LlmOptions().withPresencePenalty(0.6)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertEquals(0.6, options.presencePenalty, "Presence penalty should be preserved")
    }

    @Test
    fun `preserves frequencyPenalty`() {
        val llmo = LlmOptions().withFrequencyPenalty(0.4)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertEquals(0.4, options.frequencyPenalty, "Frequency penalty should be preserved")
    }

    @Test
    fun `converts all options except temperature`() {
        val llmo = LlmOptions()
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxTokens(1000)
            .withPresencePenalty(0.5)
            .withFrequencyPenalty(0.3)

        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)

        assertNull(options.temperature, "Temperature should be ignored")
        assertEquals(0.9, options.topP, "Top P should be preserved")
        assertEquals(1000, options.maxTokens, "Max tokens should be preserved")
        assertEquals(0.5, options.presencePenalty, "Presence penalty should be preserved")
        assertEquals(0.3, options.frequencyPenalty, "Frequency penalty should be preserved")
    }

}
