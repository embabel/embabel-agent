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
import org.springframework.ai.anthropic.AnthropicCacheOptions
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.messages.MessageType

object AnthropicOptionsConverter : OptionsConverter {

    private val logger = org.slf4j.LoggerFactory.getLogger(AnthropicOptionsConverter::class.java)

    /**
     * Anthropic's default is too low and results in truncated responses.
     */
    const val DEFAULT_MAX_TOKENS = 8192

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        val capabilities = ClaudeCapabilities.of(model)
        val builder = AnthropicChatOptions.builder()
            .model(model)
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens ?: DEFAULT_MAX_TOKENS)
            .apply {
                // Never send thinking "disabled": Claude Opus 5.5, Sonnet 5.5 and Fable 5.1 reject
                // it with a 400, and an absent field already means "off" on Claude 4.x.
                val thinkingBudget = options.thinking?.tokenBudget
                if (options.thinking?.enabled == true && thinkingBudget != null) {
                    if (capabilities.acceptsThinkingBudget) {
                        thinkingEnabled(thinkingBudget.toLong())
                    } else {
                        logger.warn(
                            "Model '{}' rejects a thinking budget, so thinking is adaptive and the budget of {} tokens is ignored",
                            model,
                            thinkingBudget,
                        )
                        thinkingAdaptive()
                    }
                }
            }
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

/**
 * What a Claude model accepts, per platform.claude.com/docs/en/build-with-claude/thinking.
 *
 * Read from the model id rather than the catalogue because BYOK callers name any model.
 * ponytail: parses family and version from the id; an id it can't parse (a gateway alias,
 * Claude 3) keeps the Claude 4.5 behaviour. Move to catalogue flags if ids stop following
 * `claude-<family>-<major>-<minor>`.
 *
 * @property acceptsThinkingBudget `thinking: {type: "enabled", budget_tokens}` works; Claude Opus 4.7
 * and later, the Claude 5 generation, Fable and Mythos reject it and take adaptive thinking instead.
 */
internal data class ClaudeCapabilities(
    val acceptsThinkingBudget: Boolean,
) {
    companion object {
        private val ID = Regex("""^claude-(opus|sonnet|haiku|fable|mythos)(?:-(\d+)(?:-(\d)(?!\d))?)?""")

        fun of(model: String): ClaudeCapabilities {
            val match = ID.find(model)
            val family = match?.groupValues?.get(1)
            // Fable and Mythos only exist in the adaptive-thinking generation.
            val version = if (family == "fable" || family == "mythos") {
                Int.MAX_VALUE
            } else {
                (match?.groupValues?.get(2)?.toIntOrNull() ?: 0) * 10 +
                    (match?.groupValues?.get(3)?.toIntOrNull() ?: 0)
            }
            return ClaudeCapabilities(
                acceptsThinkingBudget = version < 47,
            )
        }
    }
}
