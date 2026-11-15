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
package com.embabel.agent.spi.support.springai

import org.springframework.ai.chat.metadata.Usage as SpringAiUsage

/**
 * SPI reading the thinking tokens a chat model reports apart from its completion tokens, as Gemini does.
 * [toEmbabelUsage] adds them to the completion, as OpenAI and Anthropic count them.
 * Providers that count thinking inside the completion use [NONE]: a reader returning OpenAI's
 * `reasoning_tokens` here would count them twice.
 *
 * Follows the same injected-collaborator pattern as [SpringAiNativeThinkingExtractor].
 */
fun interface SpringAiThinkingTokenReader {

    fun read(usage: SpringAiUsage): Int?

    companion object {
        @JvmField
        val NONE = SpringAiThinkingTokenReader { null }
    }
}
