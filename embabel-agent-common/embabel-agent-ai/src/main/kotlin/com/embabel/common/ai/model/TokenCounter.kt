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

import org.jetbrains.annotations.ApiStatus

/**
 * Estimate the number of tokens in content of type [T].
 * Implementations must be thread-safe, stateless, and never throw.
 * Always returns >= 0.
 *
 * Parameterized so that counters can operate on different content types
 * (e.g., [String] for raw text, or message types for framing-aware estimation)
 * without coupling this SPI to higher-level abstractions.
 */
@ApiStatus.Experimental
fun interface TokenCounter<T> {

    fun countTokens(content: T): Int

    companion object {

        @JvmStatic
        fun heuristic(): TokenCounter<String> = CharacterHeuristicTokenCounter.DEFAULT
    }
}

/**
 * Estimates token count by dividing character length by a configurable
 * characters-per-token ratio. The default ratio of 4 approximates
 * tokenization for English text across most LLM tokenizers.
 * Callers working with non-Latin scripts or code may supply a
 * different ratio.
 */
@ApiStatus.Experimental
class CharacterHeuristicTokenCounter @JvmOverloads constructor(
    val charsPerToken: Int = DEFAULT_CHARS_PER_TOKEN,
) : TokenCounter<String> {

    init {
        require(charsPerToken > 0) { "charsPerToken must be positive" }
    }

    override fun countTokens(content: String): Int {
        if (content.isBlank()) return 0
        return content.length / charsPerToken
    }

    companion object {

        const val DEFAULT_CHARS_PER_TOKEN = 4

        @JvmField
        val DEFAULT = CharacterHeuristicTokenCounter()
    }
}
