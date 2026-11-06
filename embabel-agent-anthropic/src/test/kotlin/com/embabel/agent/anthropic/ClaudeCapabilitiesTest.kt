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
package com.embabel.agent.anthropic

import com.anthropic.models.messages.OutputConfig
import com.embabel.agent.api.models.AnthropicModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeCapabilitiesTest {

    @Nested
    inner class VersionParsing {

        @Test
        fun `claude-sonnet-4-5 parses to version 45`() {
            assertEquals(45, ClaudeCapabilities.of("claude-sonnet-4-5").version)
        }

        @Test
        fun `claude-opus-4-6 parses to version 46`() {
            assertEquals(46, ClaudeCapabilities.of("claude-opus-4-6").version)
        }

        @Test
        fun `claude-opus-4-8 parses to version 48`() {
            assertEquals(48, ClaudeCapabilities.of("claude-opus-4-8").version)
        }

        @Test
        fun `claude-fable-5-1 parses to version 51`() {
            assertEquals(51, ClaudeCapabilities.of("claude-fable-5-1").version)
        }

        @Test
        fun `claude-opus-5-5 parses to version 55`() {
            assertEquals(55, ClaudeCapabilities.of("claude-opus-5-5").version)
        }

        @Test
        fun `claude-sonnet-5-5 parses to version 55`() {
            assertEquals(55, ClaudeCapabilities.of("claude-sonnet-5-5").version)
        }

        @Test
        fun `claude-haiku-5-5 parses to version 55`() {
            assertEquals(55, ClaudeCapabilities.of(AnthropicModels.CLAUDE_HAIKU_5_5).version)
        }

        @Test
        fun `claude-sonnet-4-5-20250929 date-snapshot parses to version 45`() {
            assertEquals(45, ClaudeCapabilities.of("claude-sonnet-4-5-20250929").version)
        }

        @Test
        fun `unknown model parses to version 0`() {
            assertEquals(0, ClaudeCapabilities.of("unknown-model").version)
        }
    }

    @Nested
    inner class CapabilityFlags {

        @Test
        fun `claude-sonnet-4-5 does not use adaptive thinking`() {
            assertFalse(ClaudeCapabilities.of("claude-sonnet-4-5").usesAdaptiveThinking)
        }

        @Test
        fun `claude-sonnet-4-5 does not reject thinking disabled`() {
            assertFalse(ClaudeCapabilities.of("claude-sonnet-4-5").rejectsThinkingDisabled)
        }

        @Test
        fun `claude-sonnet-4-5 accepts sampling`() {
            assertTrue(ClaudeCapabilities.of("claude-sonnet-4-5").acceptsSampling)
        }

        @Test
        fun `claude-opus-4-6 does not use adaptive thinking`() {
            assertFalse(ClaudeCapabilities.of("claude-opus-4-6").usesAdaptiveThinking)
        }

        @Test
        fun `claude-opus-4-6 does not reject thinking disabled`() {
            assertFalse(ClaudeCapabilities.of("claude-opus-4-6").rejectsThinkingDisabled)
        }

        @Test
        fun `claude-opus-4-6 accepts sampling`() {
            assertTrue(ClaudeCapabilities.of("claude-opus-4-6").acceptsSampling)
        }

        @Test
        fun `claude-opus-4-8 uses adaptive thinking`() {
            assertTrue(ClaudeCapabilities.of("claude-opus-4-8").usesAdaptiveThinking)
        }

        @Test
        fun `claude-opus-4-8 does not reject thinking disabled`() {
            assertFalse(ClaudeCapabilities.of("claude-opus-4-8").rejectsThinkingDisabled)
        }

        @Test
        fun `claude-opus-4-8 does not accept sampling`() {
            assertFalse(ClaudeCapabilities.of("claude-opus-4-8").acceptsSampling)
        }

        @Test
        fun `claude-fable-5-1 uses adaptive thinking`() {
            assertTrue(ClaudeCapabilities.of("claude-fable-5-1").usesAdaptiveThinking)
        }

        @Test
        fun `claude-fable-5-1 rejects thinking disabled`() {
            assertTrue(ClaudeCapabilities.of("claude-fable-5-1").rejectsThinkingDisabled)
        }

        @Test
        fun `claude-fable-5-1 does not accept sampling`() {
            assertFalse(ClaudeCapabilities.of("claude-fable-5-1").acceptsSampling)
        }

        @Test
        fun `claude-opus-5-5 uses adaptive thinking`() {
            assertTrue(ClaudeCapabilities.of("claude-opus-5-5").usesAdaptiveThinking)
        }

        @Test
        fun `claude-opus-5-5 rejects thinking disabled`() {
            assertTrue(ClaudeCapabilities.of("claude-opus-5-5").rejectsThinkingDisabled)
        }

        @Test
        fun `claude-opus-5-5 does not accept sampling`() {
            assertFalse(ClaudeCapabilities.of("claude-opus-5-5").acceptsSampling)
        }

        @Test
        fun `claude-sonnet-5-5 uses adaptive thinking`() {
            assertTrue(ClaudeCapabilities.of("claude-sonnet-5-5").usesAdaptiveThinking)
        }

        @Test
        fun `claude-sonnet-5-5 rejects thinking disabled`() {
            assertTrue(ClaudeCapabilities.of("claude-sonnet-5-5").rejectsThinkingDisabled)
        }

        @Test
        fun `claude-sonnet-5-5 does not accept sampling`() {
            assertFalse(ClaudeCapabilities.of("claude-sonnet-5-5").acceptsSampling)
        }

        @Test
        fun `claude-haiku-5-5 model accepts thinking disabled — family exception to 5-1+ rule`() {
            assertFalse(ClaudeCapabilities.rejectsThinkingDisabled("claude-haiku-5-5"))
        }

        @Test
        fun `claude-haiku-5-5 date snapshot model accepts thinking disabled`() {
            assertFalse(ClaudeCapabilities.rejectsThinkingDisabled("claude-haiku-5-5-20990101"))
        }

        @Test
        fun `claude-sonnet-5-5 and opus-5-5 models reject thinking disabled`() {
            assertTrue(ClaudeCapabilities.rejectsThinkingDisabled("claude-sonnet-5-5"))
            assertTrue(ClaudeCapabilities.rejectsThinkingDisabled("claude-opus-5-5"))
        }

        @Test
        fun `older and unknown models accept thinking disabled`() {
            assertFalse(ClaudeCapabilities.rejectsThinkingDisabled("claude-haiku-4-5"))
            assertFalse(ClaudeCapabilities.rejectsThinkingDisabled("claude-opus-4-8"))
            assertFalse(ClaudeCapabilities.rejectsThinkingDisabled("unknown-model"))
        }

        @Test
        fun `copy of claude-haiku-5-5 capabilities behaves identically — no state outside the constructor`() {
            val haiku = ClaudeCapabilities.of("claude-haiku-5-5")
            val copied = haiku.copy()
            assertEquals(haiku, copied)
            assertEquals(haiku.rejectsThinkingDisabled, copied.rejectsThinkingDisabled)
            assertEquals(haiku.usesAdaptiveThinking, copied.usesAdaptiveThinking)
            assertEquals(haiku.acceptsSampling, copied.acceptsSampling)
            assertEquals(haiku.resolveThinking(null), copied.resolveThinking(null))
            assertEquals(haiku.resolveThinking(20000), copied.resolveThinking(20000))
        }

        @Test
        fun `capabilities are fully determined by version — equal versions behave identically`() {
            val haiku = ClaudeCapabilities.of("claude-haiku-5-5")
            val sonnet = ClaudeCapabilities.of("claude-sonnet-5-5")
            assertEquals(haiku, sonnet)
            assertEquals(haiku.rejectsThinkingDisabled, sonnet.rejectsThinkingDisabled)
        }

        @Test
        fun `claude-haiku-5-5 uses adaptive thinking and does not accept sampling`() {
            val caps = ClaudeCapabilities.of(AnthropicModels.CLAUDE_HAIKU_5_5)
            assertTrue(caps.usesAdaptiveThinking)
            assertFalse(caps.acceptsSampling)
        }

        @Test
        fun `unknown model does not use adaptive thinking`() {
            assertFalse(ClaudeCapabilities.of("unknown-model").usesAdaptiveThinking)
        }

        @Test
        fun `unknown model does not reject thinking disabled`() {
            assertFalse(ClaudeCapabilities.of("unknown-model").rejectsThinkingDisabled)
        }

        @Test
        fun `unknown model accepts sampling`() {
            assertTrue(ClaudeCapabilities.of("unknown-model").acceptsSampling)
        }
    }

    @Nested
    inner class ResolveThinking {

        @Test
        fun `no budget on old model returns Disabled`() {
            assertEquals(ThinkingMode.Disabled, ClaudeCapabilities.of("claude-sonnet-4-5").resolveThinking(null))
        }

        @Test
        fun `no budget on Claude 5-1 returns Omit`() {
            assertEquals(ThinkingMode.Omit, ClaudeCapabilities.of("claude-fable-5-1").resolveThinking(null))
        }

        @Test
        fun `no budget on Claude 5-5 returns Omit`() {
            assertEquals(ThinkingMode.Omit, ClaudeCapabilities.of("claude-sonnet-5-5").resolveThinking(null))
        }

        @Test
        fun `no budget on claude-haiku-5-5 returns Omit — unset keeps Anthropic's adaptive default`() {
            assertEquals(ThinkingMode.Omit, ClaudeCapabilities.of("claude-haiku-5-5").resolveThinking(null))
        }

        @Test
        fun `budget on claude-haiku-5-5 returns Adaptive with mapped effort`() {
            assertEquals(ThinkingMode.Adaptive(OutputConfig.Effort.HIGH), ClaudeCapabilities.of("claude-haiku-5-5").resolveThinking(8000))
        }

        @Test
        fun `budget on claude-sonnet-4-5 returns BudgetBased`() {
            assertEquals(ThinkingMode.BudgetBased(5000L), ClaudeCapabilities.of("claude-sonnet-4-5").resolveThinking(5000))
        }

        @Test
        fun `budget on claude-sonnet-5-5 returns Adaptive — thinkingEnabled rejected by 5-x with HTTP 400`() {
            assertEquals(ThinkingMode.Adaptive(OutputConfig.Effort.XHIGH), ClaudeCapabilities.of("claude-sonnet-5-5").resolveThinking(20000))
        }

        @Test
        fun `budget on claude-opus-4-8 returns Adaptive with mapped effort`() {
            assertEquals(ThinkingMode.Adaptive(OutputConfig.Effort.HIGH), ClaudeCapabilities.of("claude-opus-4-8").resolveThinking(8000))
        }
    }

    @Nested
    inner class BudgetToEffort {

        @Test
        fun `512 maps to LOW`() {
            assertEquals(OutputConfig.Effort.LOW, ClaudeCapabilities.mapBudgetToEffort(512))
        }

        @Test
        fun `1023 maps to LOW`() {
            assertEquals(OutputConfig.Effort.LOW, ClaudeCapabilities.mapBudgetToEffort(1023))
        }

        @Test
        fun `1024 maps to MEDIUM`() {
            assertEquals(OutputConfig.Effort.MEDIUM, ClaudeCapabilities.mapBudgetToEffort(1024))
        }

        @Test
        fun `4095 maps to MEDIUM`() {
            assertEquals(OutputConfig.Effort.MEDIUM, ClaudeCapabilities.mapBudgetToEffort(4095))
        }

        @Test
        fun `4096 maps to HIGH`() {
            assertEquals(OutputConfig.Effort.HIGH, ClaudeCapabilities.mapBudgetToEffort(4096))
        }

        @Test
        fun `8000 maps to HIGH`() {
            assertEquals(OutputConfig.Effort.HIGH, ClaudeCapabilities.mapBudgetToEffort(8000))
        }

        @Test
        fun `16383 maps to HIGH`() {
            assertEquals(OutputConfig.Effort.HIGH, ClaudeCapabilities.mapBudgetToEffort(16383))
        }

        @Test
        fun `16384 maps to XHIGH`() {
            assertEquals(OutputConfig.Effort.XHIGH, ClaudeCapabilities.mapBudgetToEffort(16384))
        }

        @Test
        fun `32767 maps to XHIGH`() {
            assertEquals(OutputConfig.Effort.XHIGH, ClaudeCapabilities.mapBudgetToEffort(32767))
        }

        @Test
        fun `32768 maps to MAX`() {
            assertEquals(OutputConfig.Effort.MAX, ClaudeCapabilities.mapBudgetToEffort(32768))
        }

        @Test
        fun `100000 maps to MAX`() {
            assertEquals(OutputConfig.Effort.MAX, ClaudeCapabilities.mapBudgetToEffort(100000))
        }
    }
}
