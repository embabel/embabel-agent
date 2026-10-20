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

import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.embabel.chat.MessageRole
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import org.springframework.ai.anthropic.AnthropicCacheOptions
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.messages.MessageType
import java.util.concurrent.ConcurrentHashMap

object AnthropicOptionsConverter : OptionsConverter {

    private val logger = org.slf4j.LoggerFactory.getLogger(AnthropicOptionsConverter::class.java)

    /**
     * Anthropic's default is too low and results in truncated responses.
     */
    const val DEFAULT_MAX_TOKENS = 8192

    private const val DEFAULT_TEMPERATURE = 1.0

    /** Keyed by model and ignored parameter names, so bounded by models × name subsets. */
    internal val warnedIgnoredSampling = ConcurrentHashMap.newKeySet<String>()

    /** Keyed by model ID only, so bounded by the number of models. */
    internal val warnedThinkingNotDisabled = ConcurrentHashMap.newKeySet<String>()

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        val caps = ClaudeCapabilities.of(model)
        val decision = if (isThinkingOptOut(options)) {
            if (caps.rejectsThinkingDisabled) {
                warnThinkingCannotBeDisabled(model)
                ThinkingMode.Omit
            } else {
                // No effort is set on this path, so Haiku 5.5's "disabled only at effort high or below" holds.
                ThinkingMode.Disabled
            }
        } else {
            caps.resolveThinking(resolveThinkingBudget(options))
        }
        if (!caps.acceptsSampling) {
            warnAboutIgnoredSampling(options, model)
        }
        val builder = AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(options.maxTokens ?: DEFAULT_MAX_TOKENS)
            .apply {
                when (decision) {
                    is ThinkingMode.Omit        -> Unit // do not set "thinking" — Claude 5.1+ (except Haiku) rejects thinking.type="disabled" with HTTP 400
                    is ThinkingMode.Disabled    -> thinkingDisabled()                          // send "thinking": {"type": "disabled"} — Claude ≤5.0, or explicit opt-out on Haiku 5.5
                    is ThinkingMode.Adaptive    -> { thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED); effort(decision.effort) }
                    // Claude ≥4.7: adaptive thinking. SUMMARIZED must be set explicitly — the default
                    // display on 5.x is "omitted" (empty thinking fields). SUMMARIZED is the only option
                    // that returns visible reasoning (a summarized representation; full reasoning is encrypted in
                    // the "signature" field). There is no "full" display option in the adaptive API.
                    is ThinkingMode.BudgetBased -> thinkingEnabled(decision.budgetTokens)      // send "thinking": {"type": "enabled", "budget_tokens": N}
                }
                if (caps.acceptsSampling) {
                    temperature(options.temperature)
                    topP(options.topP)
                    topK(options.topK)
                }
            }

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
     * Warn-and-drop, as for OpenAI: refusing the call would cost the caller an answer over a
     * parameter that was never essential. Default temperature is what the model uses anyway.
     * Warns once per model and set of ignored parameter names, since the same options repeat on every call.
     * Values stay out of the key so varying them cannot grow the set.
     */
    private fun warnAboutIgnoredSampling(options: LlmOptions, model: String) {
        val ignored = listOfNotNull(
            options.temperature?.takeIf { it != DEFAULT_TEMPERATURE }?.let { "temperature" to it },
            options.topP?.let { "topP" to it },
            options.topK?.let { "topK" to it },
        )
        if (ignored.isNotEmpty() && warnedIgnoredSampling.add("$model|${ignored.joinToString(",") { it.first }}")) {
            logger.warn(
                "Model '{}' rejects sampling parameters, so the following are ignored rather than sent: {}",
                model,
                ignored.joinToString(", ") { "${it.first}=${it.second}" },
            )
        }
    }

    /**
     * True when the caller explicitly turned thinking off ([LlmOptions.withoutThinking]). Not set (null) and
     * extraction-only configs (enabled=false but extractThinking=true, as used by the thinking prompt runner) are not opt-outs.
     */
    private fun isThinkingOptOut(options: LlmOptions): Boolean {
        val thinking = options.thinking ?: return false
        return !thinking.enabled && !thinking.extractThinking
    }

    /** Warns once per model: the request still runs, with Anthropic's adaptive default thinking. */
    private fun warnThinkingCannotBeDisabled(model: String) {
        if (warnedThinkingNotDisabled.add(model)) {
            logger.warn(
                "Model '{}' cannot disable thinking, so the thinking opt-out is ignored and the model's adaptive default applies",
                model,
            )
        }
    }

    /**
     * Returns the token budget if thinking is explicitly enabled with a positive budget;
     * null otherwise (thinking not requested, not enabled, or no budget specified).
     *
     * Null → no thinking: omit the field (Claude 5.1+) or send thinkingDisabled() (older models);
     * an explicit opt-out is handled before this by [isThinkingOptOut].
     * Non-null → thinking requested: use adaptive thinking (Claude ≥4.7) or budget-based thinking (older models).
     */
    private fun resolveThinkingBudget(options: LlmOptions): Int? {
        val thinking = options.thinking ?: return null
        if (thinking.enabled != true) return null
        val budget = thinking.tokenBudget ?: return null
        return if (budget > 0) budget else null
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
