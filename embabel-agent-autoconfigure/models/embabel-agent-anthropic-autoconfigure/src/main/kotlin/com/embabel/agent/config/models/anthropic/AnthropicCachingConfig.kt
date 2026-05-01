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
package com.embabel.agent.config.models.anthropic

import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.spi.InternalExtensionApi

/**
 * Extension key for Anthropic caching configuration in LlmOptions.extensions.
 */
const val ANTHROPIC_CACHING_EXTENSION = "anthropic.caching"

/**
 * Configuration for Anthropic prompt caching.
 *
 * Anthropic's prompt caching feature allows you to cache large prompt contexts
 * (system prompts, tool definitions, conversation history) to reduce costs and latency.
 *
 * Key points:
 * - Cache TTL is fixed at 5 minutes (not user-configurable)
 * - Cache reads cost 10% of regular input tokens
 * - Cache writes cost the same as regular input tokens
 * - Minimum cacheable content: 1024 tokens
 * - Minimum cache lifetime: 5 minutes
 *
 * See: https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
 *
 * @param systemPrompt Cache the system prompt. Recommended for long system prompts
 *                     that don't change between requests.
 * @param tools Cache tool definitions. Recommended when using many tools or tools
 *              with large schemas.
 * @param conversationHistory Cache conversation history. Useful for long multi-turn
 *                            conversations where earlier context is reused.
 */
data class AnthropicCachingConfig(
    var systemPrompt: Boolean = false,
    var tools: Boolean = false,
    var conversationHistory: Boolean = false,
)

/**
 * Add Anthropic caching configuration to LlmOptions.
 *
 * This extension function is only available when the embabel-agent-anthropic-autoconfigure
 * module is on the classpath.
 *
 * Example:
 * ```kotlin
 * val options = LlmOptions()
 *     .withAnthropicCaching(
 *         systemPrompt = true,
 *         tools = true
 *     )
 * ```
 *
 * @param config The caching configuration
 * @return New LlmOptions with Anthropic caching configured
 */
@OptIn(InternalExtensionApi::class)
fun LlmOptions.withAnthropicCaching(config: AnthropicCachingConfig): LlmOptions {
    return withExtension(ANTHROPIC_CACHING_EXTENSION, config)
}

/**
 * Add Anthropic caching configuration to LlmOptions using named parameters.
 *
 * Example:
 * ```kotlin
 * val options = LlmOptions()
 *     .withAnthropicCaching(
 *         systemPrompt = true,
 *         tools = true,
 *         conversationHistory = false
 *     )
 * ```
 *
 * @param systemPrompt Cache the system prompt
 * @param tools Cache tool definitions
 * @param conversationHistory Cache conversation history
 * @return New LlmOptions with Anthropic caching configured
 */
fun LlmOptions.withAnthropicCaching(
    systemPrompt: Boolean = false,
    tools: Boolean = false,
    conversationHistory: Boolean = false,
): LlmOptions {
    return withAnthropicCaching(
        AnthropicCachingConfig(
            systemPrompt = systemPrompt,
            tools = tools,
            conversationHistory = conversationHistory,
        ),
    )
}

/**
 * Get Anthropic caching configuration from LlmOptions.
 *
 * @return The caching configuration, or null if not configured
 */
@OptIn(InternalExtensionApi::class)
fun LlmOptions.getAnthropicCaching(): AnthropicCachingConfig? {
    return getExtension(ANTHROPIC_CACHING_EXTENSION)
}
