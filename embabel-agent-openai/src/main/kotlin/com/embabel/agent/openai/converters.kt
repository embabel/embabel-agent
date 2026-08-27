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
package com.embabel.agent.openai

import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.util.loggerFor
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * Shared wire/param names for the sampling fields [CapabilityAwareOpenAiOptionsConverter]
 * checks against [ModelCapabilities]. One place to keep the strings used in warnings and
 * the capability flags in sync.
 */
object SamplingParameterNames {
    const val TEMPERATURE = "temperature"
    const val TOP_P = "topP"
    const val PRESENCE_PENALTY = "presencePenalty"
    const val FREQUENCY_PENALTY = "frequencyPenalty"
}

/**
 * Declarative sampling / token-limit capabilities for a model.
 *
 * Sourced from model YAML (`special_handling` /
 * [com.embabel.agent.config.models.openai.SupportFeaturesConfiguration]) — the LLM model
 * database is the source of truth for which request params a model accepts.
 *
 * Use this type for **proactive** checks before building [LlmOptions], or rely on
 * [CapabilityAwareOpenAiOptionsConverter] to **warn and drop** unsupported values at
 * conversion time (same strategy as the GPT-5 path landed in #1874 — never fail a
 * request over a sampling parameter that was never essential).
 *
 * Consistent with the GPT-5 findings: some models reject sampling fields for their
 * presence alone, and the GPT-5 family maps the token limit to `max_completion_tokens`
 * rather than `max_tokens`.
 */
data class ModelCapabilities(
    val supportsTemperature: Boolean = true,
    val supportsTopP: Boolean = true,
    val supportsFrequencyPenalty: Boolean = true,
    val supportsPresencePenalty: Boolean = true,
    /**
     * When true, map [LlmOptions.maxTokens] → [OpenAiChatOptions.maxCompletionTokens]
     * and leave `maxTokens` unset. The GPT-5 family rejects `max_tokens` for presence alone.
     */
    val usesMaxCompletionTokens: Boolean = false,
) {
    companion object {
        @JvmField
        val DEFAULT = ModelCapabilities()

        /** Temperature-restricted only (e.g. GPT-4.1 family) — still uses `max_tokens`. */
        @JvmField
        val WITHOUT_TEMPERATURE = ModelCapabilities(supportsTemperature = false)

        /**
         * Conservative GPT-5 baseline: no sampling parameters and `max_completion_tokens`.
         * Prefer per-model YAML when a specific GPT-5 tier still accepts top_p / penalties
         * (e.g. 5.1 / 5.2 / 5.4 tiers verified live).
         */
        @JvmField
        val GPT5_FAMILY = ModelCapabilities(
            supportsTemperature = false,
            supportsTopP = false,
            supportsFrequencyPenalty = false,
            supportsPresencePenalty = false,
            usesMaxCompletionTokens = true,
        )
    }
}

/**
 * Single OpenAI options converter that consults [ModelCapabilities].
 *
 * **Warn-and-drop (not throw):** if the model does not support a sampling parameter and
 * the caller set a value that would be sent to the API, the value is omitted and a
 * warning is logged. Refusing the call outright would cost the caller an answer over a
 * parameter that was never essential (cost argument from #1852 / #1874 review).
 *
 * Default temperature (`1.0`) on temperature-restricted models is omitted without warning
 * (providers often only reject non-default temperature; 1.0 is what the model uses anyway).
 * Null parameters are never sent.
 *
 * Token limit: when [ModelCapabilities.usesMaxCompletionTokens] is true, the limit is
 * placed on `maxCompletionTokens` and `maxTokens` is left unset (GPT-5 family).
 *
 * ### Request execution chain
 *
 * ```
 * LlmOptions (caller sets e.g. temperature)
 *   → OptionsConverter.convertOptions(options, model)
 *     → CapabilityAwareOpenAiOptionsConverter.convertOptions
 *       - capability supports the field?  → pass value through to OpenAiChatOptions
 *       - capability rejects it           → warn (if non-default/non-null) and omit
 *   → Prompt.options passed to ChatModel.call
 *     → provider HTTP call
 *       - if a capability flag was missing/stale and the provider still rejects the field,
 *         InstrumentedChatModel parses structured error.param and retries once with that
 *         field stripped — a **safety net**, not the primary path.
 * ```
 */
class CapabilityAwareOpenAiOptionsConverter(
    private val capabilities: ModelCapabilities = ModelCapabilities.DEFAULT,
) : OptionsConverter {

    private val logger = loggerFor<CapabilityAwareOpenAiOptionsConverter>()

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        require(model.isNotBlank()) { "model id must not be blank" }

        warnAboutIgnoredParameters(options, model)

        val builder = OpenAiChatOptions.builder().model(model)

        if (capabilities.usesMaxCompletionTokens) {
            // GPT-5 family: max_tokens is a 400 for presence alone; use max_completion_tokens.
            options.maxTokens?.let { builder.maxCompletionTokens(it) }
        } else {
            builder.maxTokens(options.maxTokens)
        }

        if (capabilities.supportsTemperature) {
            builder.temperature(options.temperature)
        }
        // else omit — already warned when non-default

        if (capabilities.supportsTopP) {
            builder.topP(options.topP)
        }

        if (capabilities.supportsPresencePenalty) {
            builder.presencePenalty(options.presencePenalty)
        }

        if (capabilities.supportsFrequencyPenalty) {
            builder.frequencyPenalty(options.frequencyPenalty)
        }

        return builder.build()
    }

    /**
     * Dropping unsupported sampling values silently would read as the model ignoring them;
     * throwing would cost the caller an answer. Warn once per conversion when anything is dropped.
     */
    private fun warnAboutIgnoredParameters(options: LlmOptions, model: String) {
        val ignored = buildList {
            if (!capabilities.supportsTemperature) {
                // Default temperature is what the model uses anyway — not an unanswered request.
                options.temperature?.takeIf { it != DEFAULT_TEMPERATURE }?.let {
                    add("${SamplingParameterNames.TEMPERATURE}=$it")
                }
            }
            if (!capabilities.supportsTopP) {
                options.topP?.let { add("${SamplingParameterNames.TOP_P}=$it") }
            }
            if (!capabilities.supportsPresencePenalty) {
                options.presencePenalty?.let { add("${SamplingParameterNames.PRESENCE_PENALTY}=$it") }
            }
            if (!capabilities.supportsFrequencyPenalty) {
                options.frequencyPenalty?.let { add("${SamplingParameterNames.FREQUENCY_PENALTY}=$it") }
            }
        }
        if (ignored.isNotEmpty()) {
            logger.warn(
                "Model '{}' rejects some sampling parameters, so the following are ignored rather than sent: {}",
                model,
                ignored.joinToString(", "),
            )
        }
    }

    companion object {
        private const val DEFAULT_TEMPERATURE = 1.0
    }
}

/**
 * Options converter for the GPT-5 family baseline: no sampling parameters and
 * `max_completion_tokens` (see [ModelCapabilities.GPT5_FAMILY]).
 *
 * Prefer constructing [CapabilityAwareOpenAiOptionsConverter] with per-model
 * [ModelCapabilities] from YAML when a specific tier still accepts top_p / penalties.
 */
object Gpt5ChatOptionsConverter : OptionsConverter {
    private val delegate = CapabilityAwareOpenAiOptionsConverter(ModelCapabilities.GPT5_FAMILY)

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        delegate.convertOptions(options, model)
}

/**
 * Standard options converter for OpenAI models that support all sampling parameters
 * and the classic `max_tokens` field.
 */
object StandardOpenAiOptionsConverter : OptionsConverter {
    private val delegate = CapabilityAwareOpenAiOptionsConverter(ModelCapabilities.DEFAULT)

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        delegate.convertOptions(options, model)
}
