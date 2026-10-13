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
package com.embabel.agent.spi.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.chat.Message
import com.embabel.common.core.thinking.ThinkingBlock

/**
 * Decorator that merges native and prompt-extracted thinking blocks per iteration,
 * before [com.embabel.agent.spi.loop.support.DefaultToolLoop] sees the response.
 *
 * Native thinking blocks come from the provider's response directly:
 * - Native thinking (adaptive Claude ≥4.7 or budget-based Claude ≤4.6): native blocks
 *   extracted by [com.embabel.agent.spi.support.springai.SpringAiNativeThinkingExtractor].
 * - Prompt-extracted thinking: thinking appears as XML tags in the response text,
 *   captured by [promptExtractor].
 *
 * The two sources are NOT mutually exclusive on budget-based models — the same model can
 * produce both simultaneously. On 5.x adaptive models they are mutually exclusive
 * (Anthropic blocks the combination as a ToS violation).
 *
 * and each is a distinct [ThinkingBlock]. Capturing both at call-time per iteration
 * preserves correct chronological order: `iter1_native, iter1_prompt, iter2_native, iter2_prompt`
 * rather than accumulating all-native then all-prompt post-loop.
 *
 * Stateless — a new instance is created per [ToolLoopLlmOperations.doTransformWithThinking] call.
 *
 * @param delegate Underlying sender; its response already carries native blocks when
 *   [com.embabel.agent.spi.support.springai.SpringAiNativeThinkingExtractor] is wired.
 * @param promptExtractor Extracts prompt-based thinking blocks from a single response's text content.
 */
internal class ThinkingCapturingLlmMessageSender(
    private val delegate: LlmMessageSender,
    private val promptExtractor: (String) -> List<ThinkingBlock>,
) : LlmMessageSender {

    override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
        val response = delegate.call(messages, tools)
        // Merge: native blocks from provider first, then prompt-extracted blocks from text.
        // Order within one iteration is native-before-prompt by convention.
        val promptBlocks = promptExtractor(response.textContent)
        return response.copy(thinkingBlocks = response.thinkingBlocks + promptBlocks)
    }
}
