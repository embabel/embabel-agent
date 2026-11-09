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

import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.embabel.chat.MessageRole
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.ReasoningEffort
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

    /** Keyed by model ID only, so bounded by the number of models. */
    internal val warnedThinkingNotDisabled = ConcurrentHashMap.newKeySet<String>()

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions = convert(options, model, null)

    /**
     * This converter for a model whose catalog entry declares whether its thinking can be turned off.
     * [ReasoningEffort.NONE] needs that declaration: without it, NONE fails as unsupported.
     */
    fun declaring(thinkingCanBeDisabled: Boolean): OptionsConverter = object : OptionsConverter {
        override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
            convert(options, model, thinkingCanBeDisabled)
    }

    private fun convert(options: LlmOptions, model: String, thinkingCanBeDisabled: Boolean?): ChatOptions {
        val caps = ClaudeCapabilities.of(model)
        val effort = options.reasoningEffort
        val budget = resolveThinkingBudget(options)
        val thinkingModeDecision = when {
            effort == ReasoningEffort.NONE -> noneThinking(model, thinkingCanBeDisabled)
            isThinkingOptOut(options) -> thinkingOff(model)
            // Effort works with thinking on or off, so it leaves thinking at the model's default.
            effort != null && budget == null -> ThinkingMode.Omit
            else -> caps.resolveThinking(budget)
        }
        // An explicit effort wins over the effort a budget maps to.
        val outputEffort = effort?.takeIf { it != ReasoningEffort.NONE }?.let { OutputConfig.Effort.of(it.value) }
            ?: (thinkingModeDecision as? ThinkingMode.Adaptive)?.effort
        if (!caps.acceptsSampling) {
            ClaudeCapabilities.warnAboutIgnoredSampling(model, options.temperature, options.topP, options.topK)
        }
        val builder = AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(options.maxTokens ?: DEFAULT_MAX_TOKENS)
            .apply {
                when (thinkingModeDecision) {
                    is ThinkingMode.Omit        -> Unit // do not set "thinking" — Claude 5.1+ (except Haiku) rejects thinking.type="disabled" with HTTP 400, and an effort alone keeps the model's default
                    is ThinkingMode.Disabled    -> {
                        thinkingDisabled() // send "thinking": {"type": "disabled"} — Claude ≤5.0, or explicit opt-out on Haiku 5.5
                        // No outputConfig unless the caller set an effort: Spring AI replaces it wholesale when
                        // merging, so setting effort would drop a caller's or default output config (schema, effort).
                    }
                    is ThinkingMode.Adaptive    -> thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED)
                    // Claude ≥4.7: adaptive thinking. SUMMARIZED must be set explicitly — the default
                    // display on 5.x is "omitted" (empty thinking fields). SUMMARIZED is the only option
                    // that returns visible reasoning (a summarized representation; full reasoning is encrypted in
                    // the "signature" field). There is no "full" display option in the adaptive API.
                    is ThinkingMode.BudgetBased -> thinkingEnabled(thinkingModeDecision.budgetTokens)      // send "thinking": {"type": "enabled", "budget_tokens": N}
                }
                outputEffort?.let { effort(it) }
                if (caps.acceptsSampling) { // Claude <4.7
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
     * True only when the caller called [LlmOptions.withoutThinking], even if the thinking prompt runner
     * added extraction afterwards. False when thinking was never configured or is extraction-only.
     */
    private fun isThinkingOptOut(options: LlmOptions): Boolean = options.thinking?.disabled == true

    /**
     * [ReasoningEffort.NONE]: thinking disabled where the catalog declares [model] accepts it. Anywhere
     * else the request would either fail at Anthropic or keep thinking on, so it fails here instead.
     */
    private fun noneThinking(model: String, thinkingCanBeDisabled: Boolean?): ThinkingMode = when (thinkingCanBeDisabled) {
        true -> ThinkingMode.Disabled
        false -> throw UnsupportedOperationException(
            "Reasoning effort NONE is unsupported on model '$model': its catalog entry declares thinking cannot be turned off",
        )
        null -> throw UnsupportedOperationException(
            "Reasoning effort NONE is unsupported on model '$model': its catalog entry does not declare whether " +
                "thinking can be turned off (thinking.can_be_disabled in anthropic-models.yml)",
        )
    }

    /** Thinking off: disabled where [model] accepts it, otherwise omitted with a warning. */
    private fun thinkingOff(model: String): ThinkingMode =
        if (ClaudeCapabilities.rejectsThinkingDisabled(model)) {
            warnThinkingCannotBeDisabled(model)
            ThinkingMode.Omit
        } else {
            ThinkingMode.Disabled
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
