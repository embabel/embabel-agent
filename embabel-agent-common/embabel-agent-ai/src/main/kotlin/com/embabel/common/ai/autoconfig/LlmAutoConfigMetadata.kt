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
package com.embabel.common.ai.autoconfig

import com.embabel.common.ai.model.PerTokenPricingModel
import com.fasterxml.jackson.annotation.JsonAlias
import java.time.LocalDate

/**
 * Provider-native feature support for a model.
 *
 * This is metadata, not execution logic. Core Embabel does not interpret
 * provider payload tags such as response_format, json_schema, or input_schema.
 * Provider-specific configurers read this metadata and translate Embabel
 * requests into provider options or payload JSON.
 */
data class NativeSupport(
    /**
     * Native structured-output support, if this model/provider can receive a
     * schema through provider payload/options instead of prompt text alone.
     */
    @param:JsonAlias("structured-output")
    val structuredOutput: NativeStructuredOutputCapability? = null,
) {
    fun merge(defaults: NativeSupport?): NativeSupport? {
        if (defaults == null) {
            return this
        }
        return NativeSupport(
            structuredOutput = structuredOutput?.merge(defaults.structuredOutput) ?: defaults.structuredOutput,
        )
    }
}

/**
 * Provider-local native capability metadata.
 *
 * [strategy] is interpreted only by the provider module. Examples:
 * - OpenAI: "response_format" means use the response_format/json_schema payload path.
 * - Anthropic: "tool" means express the output schema as a tool input schema.
 *
 * A null property means "inherit from provider defaults".
 *
 * [promptInstructions] controls whether Embabel keeps textual converter format
 * instructions in the prompt while also sending native payload metadata.
 * This field is currently documented for provider model metadata, but the
 * native structured-output configurers still rely on Spring AI option objects
 * for actual payload mutation:
 * - "include": preserve existing prompt instructions. This is the safest default.
 * - "fallback": include prompt instructions only when native support is not active.
 * - "suppress": rely on native provider enforcement and omit prompt instructions.
 *
 * [options] contains provider/tag names used by the selected strategy. Core
 * Embabel passes these through; provider configurers decide what they mean.
 * They are currently present for documentation and future payload-tag mapping.
 */
data class NativeStructuredOutputCapability(
    val supported: Boolean? = null,
    val strategy: String? = null,
    val strict: Boolean? = null,
    @param:JsonAlias("prompt-instructions")
    val promptInstructions: String? = null,
    val options: Map<String, String> = emptyMap(),
) {
    fun merge(defaults: NativeStructuredOutputCapability?): NativeStructuredOutputCapability {
        if (defaults == null) {
            return this
        }
        return NativeStructuredOutputCapability(
            supported = supported ?: defaults.supported,
            strategy = strategy ?: defaults.strategy,
            strict = strict ?: defaults.strict,
            promptInstructions = promptInstructions ?: defaults.promptInstructions,
            options = defaults.options + options,
        )
    }
}

/**
 * Name of the LLM, such as "gpt-3.5-turbo".
 *
 * This interface defines autoconfiguration metadata for LLM models, which is separate from
 * runtime [ModelMetadata]. The purpose is to provide static, provider-specific configuration
 * data that can be loaded at application startup to automatically configure LLM clients.
 */
interface LlmAutoConfigMetadata {
    /**
     * Name of the LLM, such as "gpt-3.5-turbo"
     */
    val name: String

    /**
     * Provider-specific model identifier
     */
    val modelId: String

    /**
     * Optional display name
     */
    val displayName: String?

    /**
     * The knowledge cutoff date of the model, if known.
     */
    val knowledgeCutoffDate: LocalDate?

    /**
     * Pricing configuration
     */
    val pricingModel: PerTokenPricingModel?

    /**
     * Provider-native support metadata, if declared for this model.
     */
    val nativeSupport: NativeSupport?
        get() = null
}

/**
 * Common container for provider model configurations.
 */
interface LlmAutoConfigProvider<T : LlmAutoConfigMetadata> {
    val models: List<T>
}

/**
 * Loader interface for LLM metadata.
 */
interface LlmAutoConfigMetadataLoader<T> {
    fun loadAutoConfigMetadata(): T
}
