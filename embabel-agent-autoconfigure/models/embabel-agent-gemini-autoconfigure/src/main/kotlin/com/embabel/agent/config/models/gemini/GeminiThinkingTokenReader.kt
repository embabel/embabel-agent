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
package com.embabel.agent.config.models.gemini

import com.embabel.agent.spi.support.springai.SpringAiThinkingTokenReader
import org.springframework.ai.chat.metadata.Usage

/**
 * Reads Gemini's thinking tokens from its OpenAI-compatible endpoint.
 *
 * What the endpoint reports:
 * - `prompt_tokens`: the input.
 * - `completion_tokens`: the visible answer only, without the thinking.
 * - `total_tokens`: input + answer + thinking.
 * - no `reasoning_tokens` field.
 *
 * So the thinking is the gap:
 *
 *     thinking = total_tokens - prompt_tokens - completion_tokens
 *
 * Example from a real `gemini-3.8-flash` call (`GeminiThinkingCostIT`):
 * prompt 60, completion 1, total 233, so thinking = 233 - 60 - 1 = 172.
 *
 * Safety:
 * - If the endpoint ever counts thinking in `completion_tokens`, the gap becomes 0,
 *   so we return `null` and nothing is counted twice.
 * - Built-in Google tools would also add tokens to the total, but this endpoint
 *   cannot reach them, so the gap is only thinking.
 */
object GeminiThinkingTokenReader : SpringAiThinkingTokenReader {

    override fun read(usage: Usage): Int? {
        val total = usage.totalTokens ?: return null
        return (total - (usage.promptTokens ?: 0) - (usage.completionTokens ?: 0)).takeIf { it > 0 }
    }
}
