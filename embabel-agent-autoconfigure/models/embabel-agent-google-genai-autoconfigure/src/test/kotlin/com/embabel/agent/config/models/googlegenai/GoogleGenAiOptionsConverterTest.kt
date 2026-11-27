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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ReasoningEffort
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel

class GoogleGenAiOptionsConverterTest : OptionsConverterTestSupport(
    optionsConverter = GoogleGenAiOptionsConverter
) {

    @Nested
    inner class ReasoningEffortOptions {

        @Test
        fun `should translate supported effort levels for Gemini 3 and 4`() {
            val levels = mapOf(
                ReasoningEffort.LOW to GoogleGenAiThinkingLevel.LOW,
                ReasoningEffort.MEDIUM to GoogleGenAiThinkingLevel.MEDIUM,
                ReasoningEffort.HIGH to GoogleGenAiThinkingLevel.HIGH,
            )
            for (model in listOf(GoogleGenAiModels.GEMINI_3_6_FLASH, GoogleGenAiModels.GEMINI_4_HYDROGEN)) {
                for ((effort, level) in levels) {
                    val options = optionsConverter.convertOptions(
                        LlmOptions().withReasoningEffort(effort), model,
                    ) as GoogleGenAiChatOptions
                    assertEquals(level, options.thinkingLevel, "$model $effort")
                    assertNull(options.thinkingBudget)
                }
            }
        }

        @Test
        fun `should prefer effort to an explicit budget and retain thought extraction`() {
            val options = optionsConverter.convertOptions(
                LlmOptions()
                    .withThinking(Thinking.withTokenBudget(2000).applyExtraction())
                    .withReasoningEffort(ReasoningEffort.MEDIUM),
                GoogleGenAiModels.GEMINI_4_HYDROGEN,
            ) as GoogleGenAiChatOptions
            assertEquals(GoogleGenAiThinkingLevel.MEDIUM, options.thinkingLevel)
            assertNull(options.thinkingBudget)
            assertEquals(true, options.includeThoughts)
        }

        @Test
        fun `should use the catalog budget only when the request has no effort or budget`() {
            val converter = GoogleGenAiOptionsConverter.declaring(false, 1000)
            val model = GoogleGenAiModels.GEMINI_4_HYDROGEN
            val defaults = converter.convertOptions(LlmOptions(), model) as GoogleGenAiChatOptions
            assertEquals(1000, defaults.thinkingBudget)
            assertNull(defaults.thinkingLevel)
            val budget = converter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(2000)), model,
            ) as GoogleGenAiChatOptions
            assertEquals(2000, budget.thinkingBudget)
            val effort = converter.convertOptions(
                LlmOptions().withReasoningEffort(ReasoningEffort.LOW), model,
            ) as GoogleGenAiChatOptions
            assertEquals(GoogleGenAiThinkingLevel.LOW, effort.thinkingLevel)
            assertNull(effort.thinkingBudget)
        }

        @Test
        fun `should retain budget based thinking on Gemini 2 point 5`() {
            val options = optionsConverter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(2000)).withReasoningEffort(ReasoningEffort.LOW),
                GoogleGenAiModels.GEMINI_2_5_FLASH,
            ) as GoogleGenAiChatOptions
            assertEquals(2000, options.thinkingBudget)
            assertNull(options.thinkingLevel)
        }

        @Test
        fun `should reject effort values without a Spring AI thinking level`() {
            for (effort in listOf(ReasoningEffort.NONE, ReasoningEffort.XHIGH, ReasoningEffort.MAX)) {
                assertThrows(UnsupportedOperationException::class.java) {
                    optionsConverter.convertOptions(
                        LlmOptions().withReasoningEffort(effort), GoogleGenAiModels.GEMINI_4_HYDROGEN,
                    )
                }
            }
        }
    }

    @Test
    fun `should default to no thinking budget`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "test-model") as GoogleGenAiChatOptions
        assertNull(options.thinkingBudget)
    }

    @Test
    fun `should set thinking budget when enabled`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(2000)), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(2000, options.thinkingBudget)
    }

    @Test
    fun `should set include thoughts when thinking extraction is enabled`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withExtraction()), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(true, options.includeThoughts)
    }

    @Test
    fun `should set thinking budget and include thoughts together`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(
                Thinking.withTokenBudget(2000).applyExtraction()
            ), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(true, options.includeThoughts)
        assertEquals(2000, options.thinkingBudget)
    }

    @Test
    fun `should not set include thoughts when extraction is disabled`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(2000)), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(false, options.includeThoughts)
    }

    @Test
    fun `should not set thinking budget when thinking is null`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "test-model") as GoogleGenAiChatOptions
        assertNull(options.thinkingBudget)
    }

    @Test
    fun `should send zero thinking budget on opt-out when the model can be disabled`() {
        val options = GoogleGenAiOptionsConverter.declaring(true).convertOptions(
            LlmOptions().withoutThinking(), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(0, options.thinkingBudget)
    }

    @Test
    fun `should keep zero budget and include thoughts when opt-out is combined with extraction`() {
        val options = GoogleGenAiOptionsConverter.declaring(true).convertOptions(
            LlmOptions().withThinking(Thinking.NONE.applyExtraction()), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(0, options.thinkingBudget)
        assertEquals(true, options.includeThoughts)
    }

    @Test
    fun `should not send a budget on opt-out when the model cannot be disabled`() {
        val options = GoogleGenAiOptionsConverter.declaring(false).convertOptions(
            LlmOptions().withoutThinking(), "test-model"
        ) as GoogleGenAiChatOptions
        assertNull(options.thinkingBudget)
    }

    @Test
    fun `should not send a budget on opt-out when the catalog does not declare it`() {
        val options = GoogleGenAiOptionsConverter.convertOptions(
            LlmOptions().withoutThinking(), "test-model"
        ) as GoogleGenAiChatOptions
        assertNull(options.thinkingBudget)
    }

    @Test
    fun `declaring true leaves an explicit budget and no-thinking requests as before`() {
        val converter = GoogleGenAiOptionsConverter.declaring(true)
        val budget = converter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(2000)), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(2000, budget.thinkingBudget)
        val none = converter.convertOptions(LlmOptions(), "test-model") as GoogleGenAiChatOptions
        assertNull(none.thinkingBudget)
        val extractionOnly = converter.convertOptions(
            LlmOptions().withThinking(Thinking.withExtraction()), "test-model"
        ) as GoogleGenAiChatOptions
        assertNull(extractionOnly.thinkingBudget)
    }

    @Test
    fun `should set default maxOutputTokens`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "test-model") as GoogleGenAiChatOptions
        assertEquals(GoogleGenAiOptionsConverter.DEFAULT_MAX_OUTPUT_TOKENS, options.maxOutputTokens)
    }

    @Test
    fun `should override maxOutputTokens when specified`() {
        val options = optionsConverter.convertOptions(LlmOptions().withMaxTokens(200), "test-model") as GoogleGenAiChatOptions
        assertEquals(200, options.maxOutputTokens)
    }

    @Test
    fun `should set temperature`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTemperature(0.5), "test-model")
        assertEquals(0.5, options.temperature)
    }

    @Test
    fun `should set topP`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopP(0.9), "test-model")
        assertEquals(0.9, options.topP)
    }

    @Test
    fun `should set topK`() {
        val options = optionsConverter.convertOptions(LlmOptions().withTopK(50), "test-model")
        assertEquals(50, options.topK)
    }

    @Test
    fun `should set presencePenalty`() {
        val options = optionsConverter.convertOptions(LlmOptions().withPresencePenalty(0.3), "test-model") as GoogleGenAiChatOptions
        assertEquals(0.3, options.presencePenalty)
    }

    @Test
    fun `should set frequencyPenalty`() {
        val options = optionsConverter.convertOptions(LlmOptions().withFrequencyPenalty(0.4), "test-model") as GoogleGenAiChatOptions
        assertEquals(0.4, options.frequencyPenalty)
    }
}
