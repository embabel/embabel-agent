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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ReasoningEffort
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.slf4j.LoggerFactory
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel

class GoogleGenAiOptionsConverterTest : OptionsConverterTestSupport(
    optionsConverter = GoogleGenAiOptionsConverter
) {

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

    @Test
    fun `should send no thinking level without a reasoning effort`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "test-model") as GoogleGenAiChatOptions
        assertNull(options.thinkingLevel)
    }

    @Test
    fun `should send low to high reasoning effort as the same thinking level`() {
        mapOf(
            ReasoningEffort.LOW to GoogleGenAiThinkingLevel.LOW,
            ReasoningEffort.MEDIUM to GoogleGenAiThinkingLevel.MEDIUM,
            ReasoningEffort.HIGH to GoogleGenAiThinkingLevel.HIGH,
        ).forEach { (effort, level) ->
            val options = optionsConverter.convertOptions(
                LlmOptions().withReasoningEffort(effort), "test-model"
            ) as GoogleGenAiChatOptions
            assertEquals(level, options.thinkingLevel)
        }
    }

    @Test
    fun `should reject reasoning effort none and above high`() {
        listOf(ReasoningEffort.NONE, ReasoningEffort.XHIGH, ReasoningEffort.MAX).forEach { effort ->
            assertThrows<UnsupportedOperationException> {
                optionsConverter.convertOptions(LlmOptions().withReasoningEffort(effort), "test-model")
            }
        }
    }

    @Test
    fun `should say why reasoning effort none is rejected`() {
        val error = assertThrows<UnsupportedOperationException> {
            optionsConverter.convertOptions(LlmOptions().withReasoningEffort(ReasoningEffort.NONE), "test-model")
        }
        assertEquals(
            "Reasoning effort NONE is unsupported on model 'test-model': Gemini has no thinking level that turns thinking off",
            error.message,
        )
    }

    @Test
    fun `should send the reasoning effort when thinking is turned off on a model that cannot disable it`() {
        listOf(optionsConverter, GoogleGenAiOptionsConverter.declaring(false)).forEach { converter ->
            val options = converter.convertOptions(
                LlmOptions().withoutThinking().withReasoningEffort(ReasoningEffort.LOW), "test-model"
            ) as GoogleGenAiChatOptions
            assertEquals(GoogleGenAiThinkingLevel.LOW, options.thinkingLevel)
            assertNull(options.thinkingBudget)
        }
    }

    @Test
    fun `should send only a zero budget when thinking is turned off on a model that can disable it`() {
        val options = GoogleGenAiOptionsConverter.declaring(true).convertOptions(
            LlmOptions().withoutThinking().withReasoningEffort(ReasoningEffort.LOW), "test-model"
        ) as GoogleGenAiChatOptions
        assertEquals(0, options.thinkingBudget)
        assertNull(options.thinkingLevel, "Gemini rejects a budget and a level together")
    }

    @Test
    fun `should still reject reasoning effort none and above high when thinking is turned off`() {
        val converters = listOf(
            optionsConverter,
            GoogleGenAiOptionsConverter.declaring(false),
            GoogleGenAiOptionsConverter.declaring(true),
        )
        converters.forEach { converter ->
            listOf(ReasoningEffort.NONE, ReasoningEffort.XHIGH, ReasoningEffort.MAX).forEach { effort ->
                assertThrows<UnsupportedOperationException>("$effort") {
                    converter.convertOptions(LlmOptions().withoutThinking().withReasoningEffort(effort), "test-model")
                }
            }
        }
    }

    @Test
    fun `should send reasoning effort instead of thinking budget when both are set`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(2000).applyExtraction())
                .withReasoningEffort(ReasoningEffort.MEDIUM),
            "test-model",
        ) as GoogleGenAiChatOptions
        assertEquals(GoogleGenAiThinkingLevel.MEDIUM, options.thinkingLevel)
        assertNull(options.thinkingBudget)
        assertEquals(true, options.includeThoughts)
    }

    @Test
    fun `should name the thinking level sent when the opt-out is ignored`() {
        GoogleGenAiOptionsConverter.warnedThinkingNotDisabled.removeIf { it.startsWith("warn-no-level-model") }
        val warnings = optOutWarnings {
            optionsConverter.convertOptions(LlmOptions().withoutThinking(), "warn-no-level-model")
            optionsConverter.convertOptions(
                LlmOptions().withoutThinking().withReasoningEffort(ReasoningEffort.LOW), "warn-no-level-model"
            )
        }
        assertEquals(2, warnings.size, "expected one warning per level, got $warnings")
        assertTrue(warnings[0].endsWith("so no thinkingBudget is sent"), warnings[0])
        assertTrue(warnings[1].endsWith("so no thinkingBudget is sent; thinkingLevel LOW is sent instead"), warnings[1])
    }

    private fun optOutWarnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(GoogleGenAiOptionsConverter::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            block()
        } finally {
            logger.detachAppender(logs)
        }
        return logs.list.filter { it.level == Level.WARN && it.formattedMessage.contains("opt-out ignored") }
            .map { it.formattedMessage }
    }
}
