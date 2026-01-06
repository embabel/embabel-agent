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

import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.PlatformDefault
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byName
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byRole
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration

/**
 * Thinking config. Set on Anthropic models
 * and some Ollama models.
 */
class Thinking private constructor(
    val enabled: Boolean = false,
    val tokenBudget: Int? = null,
    val extractThinking: Boolean = false,
) {

    companion object {

        @JvmStatic
        fun withTokenBudget(withTokenBudget: Int): Thinking = Thinking(
            enabled = true,
            tokenBudget = withTokenBudget,
        )

        @JvmStatic
        fun withExtraction(): Thinking = Thinking(
            extractThinking = true,
        )

        val NONE: Thinking = Thinking(
            enabled = false,
        )
    }

    /**
     * Enable thinking block extraction for user access.
     */
    fun applyExtraction(): Thinking = Thinking(
        enabled = this.enabled,
        tokenBudget = this.tokenBudget,
        extractThinking = true,
    )

    /**
     * Configure thinking token budget.
     */
    fun applyTokenBudget(tokenBudget: Int): Thinking = Thinking(
        enabled = true,
        tokenBudget = tokenBudget,
        extractThinking = this.extractThinking,
    )
}

/**
 * Common hyperparameters for LLMs.
 */
interface LlmHyperparameters {

    @get:Schema(
        description = "The temperature to use when generating responses",
        minimum = "0.0",
        maximum = "1.0",
        required = true,
    )
    val temperature: Double?

    val frequencyPenalty: Double?

    val maxTokens: Int?

    val presencePenalty: Double?

    val topK: Int?

    val topP: Double?
}

/**
 * Portable LLM options.
 * @param modelSelectionCriteria explicit model selection criteria. If provided, overrides model and role.
 * @param model Optional model name to use. If provided, specifies an LLM by name. Takes precedence over role.
 * @param role Optional role to use for model selection. If provided, and model is not specified, used to find an LLM by role
 * @param timeout Optional timeout for this LLM call. If provided, overrides the default client timeout.
 */
@Schema(
    description = "Options for LLM use",
)
data class LlmOptions @JvmOverloads constructor(
    // We use vars to allow Spring configuration properties binding
    var modelSelectionCriteria: ModelSelectionCriteria? = null,
    var model: String? = null,
    var role: String? = null,
    override var temperature: Double? = null,
    override var frequencyPenalty: Double? = null,
    override var maxTokens: Int? = null,
    override var presencePenalty: Double? = null,
    override var topK: Int? = null,
    override var topP: Double? = null,
    var thinking: Thinking? = null,
    var timeout: Duration? = null,
) : LlmHyperparameters, HasInfoString {

    @get:Schema(
        description = "If provided, custom selection criteria for the LLM to use. If not provided, a default LLM will be used.",
        required = false,
    )
    val criteria: ModelSelectionCriteria
        get() =
            modelSelectionCriteria ?: when (model) {
                null -> null
                "default" -> PlatformDefault
                "auto" -> AutoModelSelectionCriteria
                else -> byName(model!!)
            } ?: when (role) {
                null -> null
                "default" -> PlatformDefault
                "auto" -> AutoModelSelectionCriteria
                else -> byRole(role!!)
            } ?: PlatformDefault

    /**
     * Create a copy with a default temperature for the LLM.
     * If null, uses the default temperature for the model.
     */
    fun withTemperature(temperature: Double?): LlmOptions {
        return copy(temperature = temperature)
    }

    fun withMaxTokens(maxTokens: Int): LlmOptions {
        return copy(maxTokens = maxTokens)
    }

    fun withTopK(topK: Int): LlmOptions {
        return copy(topK = topK)
    }

    fun withTopP(topP: Double): LlmOptions {
        return copy(topP = topP)
    }

    fun withFrequencyPenalty(frequencyPenalty: Double): LlmOptions {
        return copy(frequencyPenalty = frequencyPenalty)
    }

    fun withPresencePenalty(presencePenalty: Double): LlmOptions {
        return copy(presencePenalty = presencePenalty)
    }

    fun withThinking(thinking: Thinking): LlmOptions {
        return copy(thinking = thinking)
    }

    fun withoutThinking(): LlmOptions {
        return copy(thinking = Thinking.NONE)
    }

    fun withTimeout(timeout: Duration): LlmOptions {
        return copy(timeout = timeout)
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return toString().indent(indent)
    }

    companion object {

        /**
         * Create an LlmOptions instance we can build.
         */
        operator fun invoke(
        ): LlmOptions = LlmOptions(
            modelSelectionCriteria = PlatformDefault,
        )

        /**
         * Create an LlmOptions instance we can build.
         */
        operator fun invoke(
            model: String,
        ): LlmOptions = LlmOptions(
            modelSelectionCriteria = ByNameModelSelectionCriteria(model),
        )

        @JvmStatic
        fun withDefaults(): LlmOptions = LlmOptions(
            modelSelectionCriteria = PlatformDefault,
        )

        @Deprecated(
            "Use 'withModel' instead",
            ReplaceWith("withModel(model)"),
        )
        @JvmStatic
        fun fromModel(
            model: String,
        ): LlmOptions = LlmOptions(
            modelSelectionCriteria = byName(model),
        )

        /**
         * Create an LlmOptions instance with a specific model.
         * @param model The name of the model to use.
         */
        @JvmStatic
        fun withModel(
            model: String,
        ): LlmOptions = LlmOptions(
            modelSelectionCriteria = byName(model),
        )

        @JvmStatic
        fun withDefaultLlm(): LlmOptions = LlmOptions(
            modelSelectionCriteria = DefaultModelSelectionCriteria,
        )

        @JvmStatic
        fun withAutoLlm(): LlmOptions = LlmOptions(
            modelSelectionCriteria = AutoModelSelectionCriteria,
        )

        /**
         * Create an LlmOptions instance using the model given
         * a specific role.
         * You will need to define the model for the role
         * in configuration.
         * @param role The role for which to select the model.
         */
        @JvmStatic
        fun withLlmForRole(role: String): LlmOptions = LlmOptions(
            modelSelectionCriteria = byRole(role),
        )

        /**
         * Create an LlmOptions instance that will
         * select the first available LLM of the given names.
         */
        @JvmStatic
        fun withFirstAvailableLlmOf(vararg names: String): LlmOptions = LlmOptions(
            modelSelectionCriteria = FallbackByNameModelSelectionCriteria(names.toList()),
        )

        operator fun invoke(
            criteria: ModelSelectionCriteria,
        ): LlmOptions = LlmOptions(
            modelSelectionCriteria = criteria,
        )

        @JvmStatic
        fun fromCriteria(
            criteria: ModelSelectionCriteria,
        ): LlmOptions = LlmOptions(
            modelSelectionCriteria = criteria,
        )
    }
}
