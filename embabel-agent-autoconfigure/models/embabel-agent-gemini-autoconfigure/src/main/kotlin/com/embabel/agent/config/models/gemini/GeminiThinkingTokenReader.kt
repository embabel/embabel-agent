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
 * Gemini's OpenAI-compatible endpoint leaves thinking out of `completion_tokens` and sends no
 * `reasoning_tokens` detail, but counts it in `total_tokens`. The thinking is what the total holds
 * beyond the prompt and the completion. Should the endpoint start counting thinking in the
 * completion, the gap closes and nothing is counted twice. Built-in Google tools, whose prompt
 * tokens the total would also hold, are not reachable through this endpoint.
 */
object GeminiThinkingTokenReader : SpringAiThinkingTokenReader {

    override fun read(usage: Usage): Int? {
        val total = usage.totalTokens ?: return null
        return (total - (usage.promptTokens ?: 0) - (usage.completionTokens ?: 0)).takeIf { it > 0 }
    }
}
