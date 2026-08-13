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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [LlmOptions.modelName] and [LlmOptions.withDefaultsFrom] are what let a role carry
 * hyperparameters as well as a model name, so they are pinned here rather than only through
 * the resolution path that uses them.
 */
class LlmOptionsDefaultsTest {

    @Nested
    inner class ModelName {

        @Test
        fun `reads the model field, which is what configuration binding sets`() {
            assertEquals("gpt-4.1-nano", LlmOptions(model = "gpt-4.1-nano").modelName)
        }

        @Test
        fun `reads by-name selection criteria, which is what withModel sets`() {
            assertEquals("gpt-4.1-nano", LlmOptions.withModel("gpt-4.1-nano").modelName)
        }

        @Test
        fun `the model field wins when both are set`() {
            assertEquals(
                "from-field",
                LlmOptions.withModel("from-criteria").copy(model = "from-field").modelName,
            )
        }

        @Test
        fun `options naming no model report none`() {
            assertNull(LlmOptions().modelName)
            assertNull(LlmOptions.withLlmForRole("cheapest").modelName)
            assertNull(LlmOptions.withAutoLlm().modelName)
        }
    }

    @Nested
    inner class WithDefaultsFrom {

        private val defaults = LlmOptions.withModel("gpt-4.1-nano")
            .withTemperature(0.2)
            .withMaxTokens(1000)
            .withTopK(10)
            .withTopP(0.8)
            .withFrequencyPenalty(0.1)
            .withPresencePenalty(0.2)
            .withThinking(Thinking.withTokenBudget(512))
            .withTimeout(Duration.ofSeconds(90))
            .copy(role = "cheapest")

        @Test
        fun `fills in everything the caller left unset`() {
            val merged = LlmOptions().withDefaultsFrom(defaults)

            assertEquals("gpt-4.1-nano", merged.modelName)
            assertEquals("cheapest", merged.role)
            assertEquals(0.2, merged.temperature)
            assertEquals(1000, merged.maxTokens)
            assertEquals(10, merged.topK)
            assertEquals(0.8, merged.topP)
            assertEquals(0.1, merged.frequencyPenalty)
            assertEquals(0.2, merged.presencePenalty)
            assertEquals(512, merged.thinking?.tokenBudget)
            assertEquals(Duration.ofSeconds(90), merged.timeout)
        }

        @Test
        fun `keeps every value the caller set explicitly`() {
            val asked = LlmOptions()
                .withTemperature(0.9)
                .withMaxTokens(50)
                .withTopK(1)
                .withTopP(0.1)
                .withFrequencyPenalty(0.9)
                .withPresencePenalty(0.8)
                .withThinking(Thinking.NONE)
                .withTimeout(Duration.ofSeconds(5))

            val merged = asked.withDefaultsFrom(defaults)

            assertEquals(0.9, merged.temperature)
            assertEquals(50, merged.maxTokens)
            assertEquals(1, merged.topK)
            assertEquals(0.1, merged.topP)
            assertEquals(0.9, merged.frequencyPenalty)
            assertEquals(0.8, merged.presencePenalty)
            assertNull(merged.thinking?.tokenBudget)
            assertEquals(Duration.ofSeconds(5), merged.timeout)
        }

        @Test
        fun `model selection is taken from the defaults wholesale, since that is the point`() {
            // Resolving a role decides which model it means, so the caller's own model choice
            // (there is none when a role was named) must not survive the merge.
            val merged = LlmOptions.withModel("caller-choice").withDefaultsFrom(defaults)
            assertEquals("gpt-4.1-nano", merged.modelName)
        }
    }
}
