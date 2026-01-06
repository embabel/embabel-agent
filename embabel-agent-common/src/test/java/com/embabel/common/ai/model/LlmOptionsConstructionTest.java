/*
 * Copyright 2024-2025 Embabel Pty Ltd.
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
package com.embabel.common.ai.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LlmOptionsConstructionTest {

    @Test
    void demonstrateJavaConstructionFromModel() {
        var llmo1 = LlmOptions
                .withModel("gpt-4")
                .withTemperature(0.7)
                .withMaxTokens(1000);
    }

    @Test
    void demonstrateJavaConstructionFromCriteria() {
        var llmo1 = LlmOptions
                .fromCriteria(ModelSelectionCriteria.byRole("best"))
                .withTemperature(0.7)
                .withMaxTokens(1000);
    }

    @Test
    void demonstrateJavaConstructionFromDefault() {
        var llmo1 = LlmOptions
                .withDefaults()
                .withTemperature(0.7)
                .withMaxTokens(1000);
    }

    @Nested
    class ThinkingFunctionality {

        @Test
        void shouldCreateThinkingWithExtraction() {
            // Test Thinking.withExtraction() factory method
            var extractionThinking = Thinking.Companion.withExtraction();
            assertTrue(extractionThinking.getExtractThinking());
        }

        @Test
        void shouldCreateThinkingWithTokenBudget() {
            // Test Thinking.withTokenBudget() factory method
            var budgetThinking = Thinking.Companion.withTokenBudget(100);
            assertNotNull(budgetThinking);
        }

        @Test
        void shouldTestThinkingNoneViaWithoutThinking() {
            // Test accessing NONE indirectly via withoutThinking()
            var options = LlmOptions.withDefaults();
            var withoutThinking = options.withoutThinking();
            var thinkingConfig = withoutThinking.getThinking();
            assertNotNull(thinkingConfig);
            assertFalse(thinkingConfig.getExtractThinking());
        }

        @Test
        void shouldApplyExtractionToDefaultThinking() {
            // Test applyExtraction() instance method on default thinking
            var options = LlmOptions.withDefaults();
            var withoutThinking = options.withoutThinking();
            var defaultThinking = withoutThinking.getThinking();
            assertNotNull(defaultThinking);
            var applied = defaultThinking.applyExtraction();
            assertNotNull(applied);
            assertTrue(applied.getExtractThinking());
        }

        @Test
        void shouldApplyTokenBudgetToExistingThinking() {
            // Test applyTokenBudget() instance method
            var extractionThinking = Thinking.Companion.withExtraction();
            assertNotNull(extractionThinking);
            var appliedBudget = extractionThinking.applyTokenBudget(200);
            assertNotNull(appliedBudget);
            assertTrue(appliedBudget.getExtractThinking());
        }

        @Test
        void shouldConfigureLlmOptionsWithThinking() {
            // Test LlmOptions.withThinking() method
            var originalOptions = LlmOptions.withDefaults();
            var thinkingConfig = Thinking.Companion.withExtraction();
            assertNotNull(thinkingConfig);
            var withThinking = originalOptions.withThinking(thinkingConfig);

            assertNotNull(withThinking.getThinking());
            assertEquals(thinkingConfig, withThinking.getThinking());
            assertNotSame(originalOptions, withThinking);
        }

        @Test
        void shouldConfigureLlmOptionsWithoutThinking() {
            // Test LlmOptions.withoutThinking() method
            var originalOptions = LlmOptions.withDefaults();
            var withoutThinking = originalOptions.withoutThinking();

            assertNotNull(withoutThinking.getThinking());
            assertFalse(withoutThinking.getThinking().getExtractThinking());
            assertNotSame(originalOptions, withoutThinking);
        }

        @Test
        void shouldChainThinkingConfiguration() {
            // Test method chaining with thinking
            var configured = LlmOptions.withDefaults()
                    .withThinking(Thinking.Companion.withExtraction())
                    .withTemperature(0.8)
                    .withMaxTokens(500);

            assertNotNull(configured.getThinking());
            assertTrue(configured.getThinking().getExtractThinking());
        }
    }
}
