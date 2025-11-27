/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.ai.google.genai.GoogleGenAiChatOptions

class GoogleGenAiOptionsConverterTest : OptionsConverterTestSupport<GoogleGenAiChatOptions>(
    optionsConverter = GoogleGenAiOptionsConverter
) {

    @Test
    fun `should default to no thinking budget`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertNull(options.thinkingBudget)
    }

    @Test
    fun `should set thinking budget when enabled`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(2000))
        )
        assertEquals(2000, options.thinkingBudget)
    }

    @Test
    fun `should not set thinking budget when thinking is null`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertNull(options.thinkingBudget)
    }

    @Test
    fun `should set default maxOutputTokens`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertEquals(GoogleGenAiOptionsConverter.DEFAULT_MAX_OUTPUT_TOKENS, options.maxOutputTokens)
    }

    @Test
    fun `should override maxOutputTokens when specified`() {
        val options = optionsConverter.convertOptions(LlmOptions().withMaxTokens(200))
        assertEquals(200, options.maxOutputTokens)
    }

    @Test
    fun `should set temperature`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTemperature(0.5))
        assertEquals(0.5, options.temperature)
    }

    @Test
    fun `should set topP`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(0.9))
        assertEquals(0.9, options.topP)
    }

    @Test
    fun `should set topK`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopK(50))
        assertEquals(50, options.topK)
    }
}
