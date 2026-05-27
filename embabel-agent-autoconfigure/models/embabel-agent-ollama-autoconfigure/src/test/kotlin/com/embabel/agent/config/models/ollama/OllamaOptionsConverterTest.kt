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
import org.junit.jupiter.api.Test
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.api.ThinkOption

class OllamaOptionsConverterTest : OptionsConverterTestSupport<OllamaChatOptions>(
    optionsConverter = OllamaOptionsConverter
) {

    @Test
    fun `should set thinking to disabled when not provided`() {
        val options = optionsConverter.convertOptions(LlmOptions())
        assertEquals(ThinkOption.ThinkBoolean.DISABLED, options.thinkOption)
    }

    @Test
    fun `should set thinking to low when budget is under 2000`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(1000))
        )
        assertEquals(ThinkOption.ThinkLevel.LOW, options.thinkOption)
    }

    @Test
    fun `should set thinking to medium when budget is between 2000 and 4000`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(3000))
        )
        assertEquals(ThinkOption.ThinkLevel.MEDIUM, options.thinkOption)
    }

    @Test
    fun `should set thinking to high when budget is 4000 or more`() {
        val options = optionsConverter.convertOptions(
            LlmOptions().withThinking(Thinking.withTokenBudget(5000))
        )
        assertEquals(ThinkOption.ThinkLevel.HIGH, options.thinkOption)
    }
}
