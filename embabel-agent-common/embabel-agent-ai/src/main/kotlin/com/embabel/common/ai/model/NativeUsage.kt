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

/**
 * Billable token counts of one or more LLM calls, as the provider reported them.
 * Provider adapters fill it per call, before Spring AI merges usage, so no token kind is lost.
 * The kinds are disjoint: prompt tokens are [inputTokens] plus every cache count.
 *
 * @param inputTokens input tokens neither read from nor written to the prompt cache
 * @param outputTokens all output tokens, including [thinkingTokens]
 * @param cacheReadInputTokens input tokens read from the prompt cache
 * @param cacheWriteInputTokens input tokens written to the prompt cache at the short TTL (Anthropic 5m, OpenAI 30m)
 * @param cacheWriteLongInputTokens input tokens written at the long TTL (Anthropic 1h, OpenAI 12h)
 * @param thinkingTokens reasoning tokens, already counted in [outputTokens]
 */
data class NativeUsage @JvmOverloads constructor(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheWriteInputTokens: Int = 0,
    val cacheWriteLongInputTokens: Int = 0,
    val thinkingTokens: Int = 0,
) {

    val promptTokens: Int
        get() = inputTokens + cacheReadInputTokens + cacheWriteInputTokens + cacheWriteLongInputTokens

    operator fun plus(other: NativeUsage): NativeUsage = NativeUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadInputTokens = cacheReadInputTokens + other.cacheReadInputTokens,
        cacheWriteInputTokens = cacheWriteInputTokens + other.cacheWriteInputTokens,
        cacheWriteLongInputTokens = cacheWriteLongInputTokens + other.cacheWriteLongInputTokens,
        thinkingTokens = thinkingTokens + other.thinkingTokens,
    )
}
