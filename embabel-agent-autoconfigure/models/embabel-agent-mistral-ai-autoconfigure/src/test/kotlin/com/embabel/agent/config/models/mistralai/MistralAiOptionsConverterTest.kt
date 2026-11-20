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
package com.embabel.agent.config.models.mistralai

import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MistralAiOptionsConverterTest {

    @Test
    fun `should map sampling options including penalties`() {
        val llmOptions = LlmOptions()
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxTokens(1000)
            .withPresencePenalty(0.5)
            .withFrequencyPenalty(0.3)

        val result = MistralAiOptionsConverter.convertOptions(llmOptions, "mistral-large-latest")

        assertEquals("mistral-large-latest", result.model)
        assertEquals(0.7, result.temperature)
        assertEquals(0.9, result.topP)
        assertEquals(1000, result.maxTokens)
        assertEquals(0.5, result.presencePenalty)
        assertEquals(0.3, result.frequencyPenalty)
    }
}
