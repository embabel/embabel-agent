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
package com.embabel.chat

import com.embabel.common.core.thinking.ThinkingBlock

/**
 * Response from LLM operations that includes both the converted result and thinking blocks.
 *
 * This class provides access to both the final structured result and the reasoning
 * process that led to that result, enabling analysis of LLM decision-making.
 *
 * @param T The type of the converted result object
 * @property result The converted object of type T, or null if conversion failed
 * @property thinkingBlocks The reasoning content extracted from the LLM response
 */
data class ChatResponseWithThinking<T>(
    /**
     * The final converted result object.
     *
     * This contains the structured output after parsing and converting the
     * cleaned LLM response (with thinking blocks removed).
     */
    val result: T?,

    /**
     * The thinking blocks extracted from the LLM response.
     *
     * Contains all reasoning, analysis, and thought processes that the LLM
     * expressed before producing the final result. Each block includes
     * metadata about the thinking pattern used.
     */
    val thinkingBlocks: List<ThinkingBlock>
) {
    /**
     * Check if the conversion was successful.
     */
    fun hasResult(): Boolean = result != null

    /**
     * Check if thinking blocks were found in the response.
     */
    fun hasThinking(): Boolean = thinkingBlocks.isNotEmpty()

    /**
     * Get all thinking content as a single concatenated string.
     * Useful for logging or display purposes.
     */
    fun getThinkingContent(): String = thinkingBlocks.joinToString("\n") { it.content }

    /**
     * Get thinking blocks of a specific type.
     */
    fun getThinkingByType(tagType: com.embabel.common.core.thinking.ThinkingTagType): List<ThinkingBlock> =
        thinkingBlocks.filter { it.tagType == tagType }

    /**
     * Get thinking blocks by tag value (e.g., "think", "analysis").
     */
    fun getThinkingByTag(tagValue: String): List<ThinkingBlock> =
        thinkingBlocks.filter { it.tagValue == tagValue }
}
