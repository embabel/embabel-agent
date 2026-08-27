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
package com.embabel.agent.config.models.ollama

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.api.ThinkOption

class OllamaOptionsConverterTest : OptionsConverterTestSupport(
    optionsConverter = OllamaOptionsConverter()
) {

    @Test
    fun `should disable thinking when no thinking config provided`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "test-model") as OllamaChatOptions
        assertEquals(ThinkOption.ThinkBoolean.DISABLED, options.thinkOption)
    }

    @Test
    fun `should omit think parameter for withExtraction (prompt-driven thinking)`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withExtraction()), "test-model"
        ) as OllamaChatOptions
        assertNull(options.thinkOption)
    }

    @Nested
    inner class BooleanThinkingTest {
        private val converter = OllamaOptionsConverter(thinkLevelsSupported = false)

        @Test
        fun `any budget enables thinking as boolean`() {
            listOf(1000, 2000, 3000, 4000, 5000).forEach { budget ->
                val options = converter.convertOptions(
                    LlmOptions().withThinking(Thinking.withTokenBudget(budget)), "test-model"
                ) as OllamaChatOptions
                assertEquals(ThinkOption.ThinkBoolean.ENABLED, options.thinkOption,
                    "Expected ENABLED for budget=$budget")
            }
        }

        @Test
        fun `no thinking config disables thinking`() {
            val options = converter.convertOptions(LlmOptions(), "test-model") as OllamaChatOptions
            assertEquals(ThinkOption.ThinkBoolean.DISABLED, options.thinkOption)
        }

        @Test
        fun `withExtraction omits think parameter`() {
            val options = converter.convertOptions(
                LlmOptions().withThinking(Thinking.withExtraction()), "test-model"
            ) as OllamaChatOptions
            assertNull(options.thinkOption)
        }
    }

    @Nested
    inner class LevelThinkingTest {
        private val converter = OllamaOptionsConverter(thinkLevelsSupported = true)

        @Test
        fun `budget under 2000 maps to low`() {
            val options = converter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(1000)), "test-model"
            ) as OllamaChatOptions
            assertEquals(ThinkOption.ThinkLevel.LOW, options.thinkOption)
        }

        @Test
        fun `budget between 2000 and 4000 maps to medium`() {
            val options = converter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(3000)), "test-model"
            ) as OllamaChatOptions
            assertEquals(ThinkOption.ThinkLevel.MEDIUM, options.thinkOption)
        }

        @Test
        fun `budget 4000 or more maps to high`() {
            val options = converter.convertOptions(
                LlmOptions().withThinking(Thinking.withTokenBudget(5000)), "test-model"
            ) as OllamaChatOptions
            assertEquals(ThinkOption.ThinkLevel.HIGH, options.thinkOption)
        }

        @Test
        fun `no thinking config disables thinking`() {
            val options = converter.convertOptions(LlmOptions(), "test-model") as OllamaChatOptions
            assertEquals(ThinkOption.ThinkBoolean.DISABLED, options.thinkOption)
        }

        @Test
        fun `withExtraction omits think parameter`() {
            val options = converter.convertOptions(
                LlmOptions().withThinking(Thinking.withExtraction()), "test-model"
            ) as OllamaChatOptions
            assertNull(options.thinkOption)
        }
    }
}
