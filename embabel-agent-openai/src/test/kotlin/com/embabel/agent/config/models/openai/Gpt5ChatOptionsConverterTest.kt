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
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * [Gpt5ChatOptionsConverter] is the conservative GPT-5 family alias:
 * no sampling parameters, token limit on `max_completion_tokens`.
 *
 * Per-model YAML may be looser for tiers that still accept top_p / penalties
 * (e.g. 5.1 / 5.2 / 5.4); those go through [com.embabel.agent.openai.CapabilityAwareOpenAiOptionsConverter]
 * with flags from `special_handling`. Unsupported values are warned and dropped
 * (not thrown) — same strategy as #1874.
 */
class Gpt5ChatOptionsConverterTest {

    @Test
    fun `ignores temperature`() {
        val llmo = LlmOptions().withTemperature(temperature = 0.5)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo, "test-model")
        // Spring AI 2.0's OpenAiChatOptions package is @NullMarked, so Kotlin treats
        // getTemperature() as returning non-null Double — direct property access
        // would NPE on a null runtime value. Read into a Double? local to bypass.
        val temperature: Double? = options.temperature
        assertNull(temperature, "Custom temperature should be ignored for GPT-5")
    }

    /**
     * Sampling parameters are refused as a block, not one by one, and each with the same 400 the
     * token limit used to earn:
     * `400 Unsupported parameter: 'top_p' is not supported with this model.`
     *
     * Verified against the live API on 2026-08-05 for gpt-5, gpt-5-mini, gpt-5-nano,
     * gpt-5.3-chat-latest, gpt-5.5 and the three gpt-5.6 tiers: `temperature`, `top_p`,
     * `presence_penalty` and `frequency_penalty` are each rejected for their presence alone. So none
     * of them may be sent — dropping only `temperature` still 400s the moment a caller sets a
     * `topP`.
     */
    @Test
    fun `sends no sampling parameter at all, since the model refuses every one of them`() {
        val llmo = LlmOptions.withModel(OpenAiModels.GPT_5)
            .withTopP(.2)
            .withPresencePenalty(0.6)
            .withFrequencyPenalty(0.4)

        val options = Gpt5ChatOptionsConverter.convertOptions(llmo, "test-model")

        val topP: Double? = options.topP
        val presencePenalty: Double? = options.presencePenalty
        val frequencyPenalty: Double? = options.frequencyPenalty
        assertNull(topP, "top_p is a 400 for these models")
        assertNull(presencePenalty, "presence_penalty is a 400 for these models")
        assertNull(frequencyPenalty, "frequency_penalty is a 400 for these models")
    }

    @Disabled("We not support thinking effort yet")
    @Test
    fun `supports thinking effort`() {
    }

    @Test
    fun `handles temperature equal to 1_0 without warning`() {
        val llmo = LlmOptions().withTemperature(temperature = 1.0)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo, "test-model")
        val temperature: Double? = options.temperature
        assertNull(temperature, "Temperature 1.0 should be ignored silently")
    }

    @Test
    fun `handles null temperature`() {
        val llmo = LlmOptions()
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo, "test-model")
        val temperature: Double? = options.temperature
        assertNull(temperature, "Null temperature should remain null")
    }

    /**
     * The GPT-5 family rejects `max_tokens` outright:
     * `400 Unsupported parameter: 'max_tokens' is not supported with this model.
     * Use 'max_completion_tokens' instead.`
     */
    @Test
    fun `carries a token limit on maxCompletionTokens, which is the field GPT-5 accepts`() {
        val options = openAiOptionsFor(LlmOptions().withMaxTokens(500))

        assertEquals(500, options.maxCompletionTokens, "The limit must still reach the model")

        val maxTokens: Int? = options.maxTokens
        assertNull(maxTokens, "Sending max_tokens at all is a 400 for every GPT-5 model")
    }

    @Test
    fun `sends neither token field when no limit was asked for`() {
        val options = openAiOptionsFor(LlmOptions())

        val maxCompletionTokens: Int? = options.maxCompletionTokens
        val maxTokens: Int? = options.maxTokens
        assertNull(maxCompletionTokens)
        assertNull(maxTokens)
    }

    /**
     * The token limit is the one hyperparameter these models do take, so a request built from a
     * fully populated [LlmOptions] carries it and nothing else.
     */
    @Test
    fun `keeps the token limit and drops everything else`() {
        val llmo = LlmOptions()
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxTokens(1000)
            .withPresencePenalty(0.5)
            .withFrequencyPenalty(0.3)

        val options = openAiOptionsFor(llmo)

        assertEquals(1000, options.maxCompletionTokens, "Max tokens should be preserved")
        val temperature: Double? = options.temperature
        val topP: Double? = options.topP
        val presencePenalty: Double? = options.presencePenalty
        val frequencyPenalty: Double? = options.frequencyPenalty
        assertNull(temperature, "Temperature should be ignored")
        assertNull(topP, "Top P should be ignored")
        assertNull(presencePenalty, "Presence penalty should be ignored")
        assertNull(frequencyPenalty, "Frequency penalty should be ignored")
    }

    /**
     * The converter returns the `ChatOptions` interface since #1857, which has no
     * `maxCompletionTokens`. Only the OpenAI subtype carries the field the GPT-5 family accepts.
     */
    private fun openAiOptionsFor(llmOptions: LlmOptions): OpenAiChatOptions =
        Gpt5ChatOptionsConverter.convertOptions(llmOptions, "test-model") as OpenAiChatOptions
}
