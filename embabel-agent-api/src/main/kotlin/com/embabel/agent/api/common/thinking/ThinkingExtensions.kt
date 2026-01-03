/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.PromptRunnerOperations
import com.embabel.agent.api.common.support.OperationContextPromptRunner

/**
 * Enhance a prompt runner with thinking block extraction capabilities.
 *
 * This extension method provides access to thinking-aware prompt operations
 * that return both converted results and the reasoning content that LLMs
 * generate during their processing.
 *
 * ## Usage
 *
 * ```kotlin
 * val result = promptRunner.withThinking().createObject("analyze this", Person::class.java)
 * val person = result.result        // The converted Person object
 * val thinking = result.thinkingBlocks // List of reasoning blocks
 * ```
 *
 * ## Implementation Behavior
 *
 * ### OperationContextPromptRunner (Production)
 * - **Real thinking extraction**: Uses ChatClientLlmOperations to extract thinking blocks
 * - **Full functionality**: Returns actual thinking content from LLM responses
 * - **Return type**: `ChatResponseWithThinking<T>` with populated thinking blocks
 *
 * ### StreamingPromptRunner (Graceful Degradation)
 * - **Empty thinking blocks**: Returns wrapper with `thinkingBlocks = emptyList()`
 * - **Preserved results**: Original response content is maintained
 * - **Return type**: `ChatResponseWithThinking<T>` with empty thinking blocks
 * - **Correct alternative**: Use `StreamingPromptRunnerOperations.createObjectStreamWithThinking()`
 *   which returns `Flux<StreamingEvent<T>>` including thinking events
 *
 * ### Other Implementations (Fallback)
 * - **Empty thinking blocks**: Returns wrapper with `thinkingBlocks = emptyList()`
 * - **Preserved results**: Original response content is maintained
 * - **Use cases**: Testing (FakePromptRunner), custom implementations
 *
 * ## Thinking Extraction Formats
 *
 * When supported, automatically extracts thinking content in various formats:
 * - Tagged thinking: `<think>reasoning here</think>`, `<analysis>content</analysis>`
 * - Prefix thinking: `//THINKING: reasoning here`
 * - Untagged thinking: raw text content before JSON objects
 *
 * ## Performance
 *
 * The thinking extraction process adds minimal overhead as it reuses existing
 * content parsing logic and only activates when thinking blocks are detected.
 *
 * @return Enhanced prompt runner operations with thinking block extraction.
 *         For OperationContextPromptRunner: real thinking extraction.
 *         For other implementations: graceful degradation with empty thinking blocks.
 *
 * @see ThinkingPromptRunnerOperations for available thinking-aware methods
 * @see com.embabel.chat.ChatResponseWithThinking for response structure
 * @see com.embabel.common.core.thinking.ThinkingBlock for thinking content details
 * @see com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations.createObjectStreamWithThinking for streaming alternative
 */
fun PromptRunnerOperations.withThinking(): ThinkingPromptRunnerOperations {
    if (this is OperationContextPromptRunner) {
        return this.withThinking()
    }

    // For other PromptRunnerOperations implementations, fall back to wrapper
    return ThinkingPromptRunner(this)
}
