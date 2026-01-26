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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.PromptRunner
import com.embabel.common.core.thinking.ThinkingResponse

/**
 * User-facing interface for executing prompts with thinking block extraction.
 *
 * This interface provides thinking-aware versions of standard prompt operations,
 * returning both the converted results and the reasoning content that LLMs
 * generated during their processing.
 *
 * ## Usage
 *
 * Access this interface through the `withThinking()` extension:
 * ```kotlin
 * val result = promptRunner.withThinking().createObject("analyze this", Person::class.java)
 * val person = result.result        // The converted Person object
 * val thinking = result.thinkingBlocks // List of reasoning blocks
 * ```
 *
 * ## Thinking Block Extraction
 *
 * This interface automatically extracts thinking content in various formats:
 * - Tagged thinking: `<think>reasoning here</think>`, `<analysis>content</analysis>`
 * - Prefix thinking: `//THINKING: reasoning here`
 * - Untagged thinking: raw text content before JSON objects
 *
 * ## Relationship to Regular Operations
 *
 * Unlike [com.embabel.agent.api.common.PromptRunnerOperations] which returns
 * direct objects, all methods in this interface return [ThinkingResponse]
 * wrappers that provide access to both results and reasoning.
 *
 * @see com.embabel.agent.api.common.PromptRunnerOperations for standard operations
 * @see ThinkingResponse for the response wrapper
 * @see com.embabel.common.core.thinking.ThinkingBlock for thinking content details
 */
@Deprecated(
    message = "Use PromptRunner.Thinking instead",
    replaceWith = ReplaceWith(
        expression = "PromptRunner.Thinking",
        imports = arrayOf("com.embabel.agent.api.common.PromptRunner.Thinking"),
    )
)
interface ThinkingPromptRunnerOperations : PromptRunner.Thinking
