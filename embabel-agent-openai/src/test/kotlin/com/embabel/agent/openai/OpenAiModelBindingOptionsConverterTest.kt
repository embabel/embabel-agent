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
import com.embabel.common.ai.model.OptionsConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import java.lang.reflect.Proxy

/**
 * Regression tests for [withOpenAiModel], which pins the resolved service model onto
 * request-level OpenAI chat options while preserving delegate converter output.
 */
class OpenAiModelBindingOptionsConverterTest {

    @Test
    fun `binds model for OpenAI compatible converter and preserves options`() {
        // Prepare
        val converter = OpenAiChatOptionsConverter.withOpenAiModel("gemini-2.5-flash")
        val llmOptions = LlmOptions()
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxTokens(1000)
            .withPresencePenalty(0.5)
            .withFrequencyPenalty(0.3)

        // Execute
        val chatOptions = converter.convertOptions(llmOptions)

        // Verify
        assertEquals("gemini-2.5-flash", chatOptions.model)
        assertEquals(0.7, chatOptions.temperature)
        assertEquals(0.9, chatOptions.topP)
        assertEquals(1000, chatOptions.maxTokens)
        assertEquals(0.5, chatOptions.presencePenalty)
        assertEquals(0.3, chatOptions.frequencyPenalty)
    }

    @Test
    fun `binds model for standard OpenAI converter`() {
        // Prepare
        val converter = StandardOpenAiOptionsConverter.withOpenAiModel("gpt-4.1")

        // Execute
        val chatOptions = converter.convertOptions(LlmOptions().withTemperature(0.4))

        // Verify
        assertEquals("gpt-4.1", chatOptions.model)
        assertEquals(0.4, chatOptions.temperature)
    }

    @Test
    fun `binds model for GPT-5 converter while preserving temperature omission`() {
        // Prepare
        val converter = Gpt5ChatOptionsConverter.withOpenAiModel("gpt-5")

        // Execute
        val chatOptions = converter.convertOptions(LlmOptions().withTemperature(0.4))

        // Verify
        assertEquals("gpt-5", chatOptions.model)
        val temperature: Double? = chatOptions.temperature
        assertNull(temperature)
    }

    @Test
    fun `rejects blank model`() {
        // Prepare
        val blankModel = " "

        // Execute
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatOptionsConverter.withOpenAiModel(blankModel)
        }

        // Verify
        assertEquals("OpenAI-compatible model must not be blank", thrown.message)
    }

    @Test
    fun `rejects non OpenAI chat options`() {
        // Prepare
        val converter = OptionsConverter {
            ToolCallingChatOptions.builder().build()
        }

        // Execute
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            converter.withOpenAiModel("gemini-2.5-flash").convertOptions(LlmOptions())
        }

        // Verify
        assertTrue(thrown.message!!.contains("gemini-2.5-flash"))
        assertTrue(thrown.message!!.contains("ToolCallingChatOptions"))
    }

    @Test
    fun `rejects null converted options without masking the error`() {
        // Prepare
        val converter = Proxy.newProxyInstance(
            OptionsConverter::class.java.classLoader,
            arrayOf(OptionsConverter::class.java),
        ) { _, _, _ -> null } as OptionsConverter<ChatOptions>

        // Execute
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            converter.withOpenAiModel("gemini-2.5-flash").convertOptions(LlmOptions())
        }

        // Verify
        assertTrue(thrown.message!!.contains("gemini-2.5-flash"))
        assertTrue(thrown.message!!.contains("null"))
    }
}
