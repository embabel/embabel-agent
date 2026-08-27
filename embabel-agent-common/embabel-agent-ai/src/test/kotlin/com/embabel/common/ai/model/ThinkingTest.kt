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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ThinkingTest {

    @Nested
    inner class FactoryMethods {

        @Test
        fun `withIncludedTags sets includedTags and enables extraction`() {
            val thinking = Thinking.withIncludedTags("analysis", "plan")
            assertEquals(setOf("analysis", "plan"), thinking.includedTags)
            assertNull(thinking.excludedTags)
            assertTrue(thinking.extractThinking)
            assertTrue(thinking.injectSystemPrompt)
        }

        @Test
        fun `withExcludedTags sets excludedTags and enables extraction`() {
            val thinking = Thinking.withExcludedTags("think")
            assertEquals(setOf("think"), thinking.excludedTags)
            assertNull(thinking.includedTags)
            assertTrue(thinking.extractThinking)
            assertTrue(thinking.injectSystemPrompt)
        }
    }

    @Nested
    inner class Propagation {

        @Test
        fun `applyExtraction preserves includedTags and excludedTags`() {
            val withIncluded = Thinking.withIncludedTags("analysis").applyExtraction()
            assertNotNull(withIncluded.includedTags)
            assertTrue(withIncluded.extractThinking)

            val withExcluded = Thinking.withExcludedTags("think").applyExtraction()
            assertNotNull(withExcluded.excludedTags)
            assertTrue(withExcluded.extractThinking)
        }

        @Test
        fun `applyTokenBudget preserves includedTags and excludedTags and injectSystemPrompt`() {
            val thinking = Thinking.withIncludedTags("analysis").applyTokenBudget(4000)
            assertEquals(setOf("analysis"), thinking.includedTags)
            assertEquals(4000, thinking.tokenBudget)
            assertTrue(thinking.injectSystemPrompt)
        }

        @Test
        fun `applyExtraction preserves injectSystemPrompt false`() {
            val thinking = Thinking.withIncludedTags("analysis")
                .withInjectSystemPrompt(false)
                .applyExtraction()
            assertFalse(thinking.injectSystemPrompt)
            assertTrue(thinking.extractThinking)
        }
    }

    @Nested
    inner class InjectSystemPrompt {

        @Test
        fun `withInjectSystemPrompt false disables injection`() {
            val thinking = Thinking.withIncludedTags("plan").withInjectSystemPrompt(false)
            assertFalse(thinking.injectSystemPrompt)
            assertEquals(setOf("plan"), thinking.includedTags)
        }

        @Test
        fun `default injectSystemPrompt is true`() {
            assertTrue(Thinking.withIncludedTags("plan").injectSystemPrompt)
            assertTrue(Thinking.withExtraction().injectSystemPrompt)
        }
    }
}
