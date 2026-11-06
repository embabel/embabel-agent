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

import com.embabel.common.core.thinking.ThinkingBlock
import org.springframework.ai.chat.model.ChatResponse

/**
 * SPI for extracting native thinking blocks from a Spring AI [ChatResponse].
 *
 * Providers that expose reasoning natively (e.g. Anthropic thinking blocks,
 * OpenAI reasoningContent) wire a concrete implementation; others use [NOOP].
 *
 * Follows the same injected-collaborator pattern as [SpringAiNativeStructuredOutputConfigurer].
 */
fun interface SpringAiNativeThinkingExtractor {

    fun extract(response: ChatResponse): List<ThinkingBlock>

    companion object {
        val NOOP = SpringAiNativeThinkingExtractor { emptyList() }
    }
}
