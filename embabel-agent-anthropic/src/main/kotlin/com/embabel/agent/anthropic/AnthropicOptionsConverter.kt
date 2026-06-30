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
package com.embabel.agent.anthropic

import com.embabel.chat.MessageRole
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
import org.springframework.ai.chat.messages.MessageType

object AnthropicOptionsConverter : OptionsConverter<AnthropicChatOptions> {

    private val logger = org.slf4j.LoggerFactory.getLogger(AnthropicOptionsConverter::class.java)

    /**
     * Anthropic's default is too low and results in truncated responses.
     */
    const val DEFAULT_MAX_TOKENS = 8192

    override fun convertOptions(options: LlmOptions): AnthropicChatOptions {
        val builder = AnthropicChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens ?: DEFAULT_MAX_TOKENS)
            .thinking(
                if (options.thinking?.enabled == true) AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                    AnthropicApi.ThinkingType.ENABLED,
                    options.thinking!!.tokenBudget,
                ) else AnthropicApi.ChatCompletionRequest.ThinkingConfig(
                    AnthropicApi.ThinkingType.DISABLED,
                    null,
                )
            )
            .topK(options.topK)

        // Apply Anthropic caching if configured
        options.getAnthropicCaching()?.let { caching ->
            val strategy = resolveStrategy(caching)
            logger.debug("Applying Anthropic caching: config={}, strategy={}", caching, strategy)

            val cacheOptionsBuilder = AnthropicCacheOptions.builder()
                .strategy(strategy)

            // Apply message type minimum content lengths
            caching.messageTypeMinContentLengths.forEach { (role, minLength) ->
                cacheOptionsBuilder.messageTypeMinContentLength(toMessageType(role), minLength)
            }

            // Apply message type TTLs
            caching.messageTypeTtls.forEach { (role, ttl) ->
                cacheOptionsBuilder.messageTypeTtl(toMessageType(role), ttl)
            }

            builder.cacheOptions(cacheOptionsBuilder.build())
        }

        return builder.build()
    }

    /**
     * Resolve Anthropic cache strategy from caching configuration.
     *
     * Strategy selection follows this priority:
     * 1. CONVERSATION_HISTORY - if conversation caching enabled
     * 2. SYSTEM_AND_TOOLS - if both system and tools enabled
     * 3. SYSTEM_ONLY - if only system enabled
     * 4. TOOLS_ONLY - if only tools enabled
     * 5. NONE - if nothing enabled
     */
    private fun resolveStrategy(config: AnthropicCachingConfig): AnthropicCacheStrategy {
        return when {
            config.conversationHistory -> AnthropicCacheStrategy.CONVERSATION_HISTORY
            config.systemPrompt && config.tools -> AnthropicCacheStrategy.SYSTEM_AND_TOOLS
            config.systemPrompt -> AnthropicCacheStrategy.SYSTEM_ONLY
            config.tools -> AnthropicCacheStrategy.TOOLS_ONLY
            else -> AnthropicCacheStrategy.NONE
        }
    }

    /**
     * Convert MessageRole to Spring AI's MessageType.
     */
    private fun toMessageType(role: MessageRole): MessageType {
        return when (role) {
            MessageRole.SYSTEM -> MessageType.SYSTEM
            MessageRole.USER -> MessageType.USER
            MessageRole.ASSISTANT -> MessageType.ASSISTANT
        }
    }
}
