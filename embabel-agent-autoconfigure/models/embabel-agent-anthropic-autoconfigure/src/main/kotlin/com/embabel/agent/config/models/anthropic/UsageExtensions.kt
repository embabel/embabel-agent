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
@file:JvmName("AnthropicUsage")
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.core.Usage
import org.springframework.ai.anthropic.api.AnthropicApi

/**
 * Extension functions for convenient access to Anthropic-specific usage metrics,
 * particularly prompt caching information.
 */

/**
 * Get the number of input tokens used to create a cache.
 * Returns null if this is not an Anthropic usage object or if no cache was created.
 *
 * Cache creation tokens are charged at a premium (25% for 5-minute TTL, higher for 1-hour TTL)
 * over regular input tokens.
 */
fun Usage.anthropicCacheCreationTokens(): Int? {
    return (nativeUsage as? AnthropicApi.Usage)?.cacheCreationInputTokens()
}

/**
 * Get the number of input tokens read from cache.
 * Returns null if this is not an Anthropic usage object or if no cache was read.
 *
 * Cache read tokens cost 90% less than regular input tokens, providing significant savings.
 */
fun Usage.anthropicCacheReadTokens(): Int? {
    return (nativeUsage as? AnthropicApi.Usage)?.cacheReadInputTokens()
}

/**
 * Check if this usage includes cache creation.
 * Returns true if cache creation tokens > 0.
 */
fun Usage.hasAnthropicCacheCreation(): Boolean {
    return (anthropicCacheCreationTokens() ?: 0) > 0
}

/**
 * Check if this usage includes cache reads.
 * Returns true if cache read tokens > 0.
 */
fun Usage.hasAnthropicCacheRead(): Boolean {
    return (anthropicCacheReadTokens() ?: 0) > 0
}

/**
 * Get a summary string of Anthropic cache usage.
 * Useful for logging and debugging.
 *
 * Example output: "cache[creation=1061, read=0]" or "cache[none]"
 */
fun Usage.anthropicCacheSummary(): String {
    val creation = anthropicCacheCreationTokens()
    val read = anthropicCacheReadTokens()

    return when {
        creation != null || read != null -> "cache[creation=${creation ?: 0}, read=${read ?: 0}]"
        else -> "cache[none]"
    }
}
