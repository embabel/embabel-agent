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
import com.embabel.common.ai.model.spi.InternalExtensionApi
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indent
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration
import kotlin.jvm.internal.DefaultConstructorMarker

/**
 * Thinking config. Set on Anthropic models
 * and some Ollama models.
 *
 * @property includedTags when set, only [ThinkingBlock][com.embabel.common.core.thinking.ThinkingBlock]s
 *   whose tag name is in this set are returned. PREFIX and NO_PREFIX blocks are suppressed.
 * @property excludedTags when set, [ThinkingBlock][com.embabel.common.core.thinking.ThinkingBlock]s
 *   whose tag name is in this set are dropped. PREFIX and NO_PREFIX blocks pass through.
 * @property injectSystemPrompt when true and [includedTags] is set, a system prompt hint is injected
 *   instructing the model to wrap reasoning in the first included tag.
 */
class Thinking private constructor(
    @JsonProperty("enabled") enabled: Boolean? = null,
    val tokenBudget: Int? = null,
    val extractThinking: Boolean = false,
    val includedTags: Set<String>? = null,
    val excludedTags: Set<String>? = null,
    val injectSystemPrompt: Boolean = true,
    @JsonProperty("optOut") optOut: Boolean = false,
) {

    /**
     * The thinking mode the caller chose: null when it chose none, so JSON and configuration binding
     * keep [NONE]'s `false` apart from extraction-only thinking. Omitted from JSON when null, since
     * readers that map `enabled` to a primitive boolean reject `null` but default a missing field to false.
     *
     * Extraction-only thinking used to be written and configured as `enabled=false`, so `enabled=false` with
     * extraction and no budget reads as no mode unless `optOut` marks it as [NONE] with extraction applied.
     */
    @field:JsonProperty("enabled")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    private val mode: Boolean? =
        if (enabled == false && extractThinking && tokenBudget == null && !optOut) null else enabled

    /**
     * Written as `optOut: true` only for [NONE] with extraction applied, the one state whose `enabled=false`
     * would otherwise read as extraction-only. Readers before it ignore it unless they fail on unknown properties.
     */
    @field:JsonProperty("optOut")
    @field:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private val optOutMarker: Boolean = mode == false && extractThinking

    @get:JsonIgnore
    val enabled: Boolean get() = mode == true

    /** True when the caller turned thinking off with [NONE], even with extraction applied on top. */
    @get:JsonIgnore
    val disabled: Boolean get() = mode == false

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

        @JvmStatic
        fun withIncludedTags(vararg tags: String): Thinking = Thinking(
            extractThinking = true,
            includedTags = tags.toSet(),
        )

        @JvmStatic
        fun withExcludedTags(vararg tags: String): Thinking = Thinking(
            extractThinking = true,
            excludedTags = tags.toSet(),
        )

        val NONE: Thinking = Thinking(
            enabled = false,
        )
    }

    /**
     * Enable thinking block extraction for user access.
     */
    fun applyExtraction(): Thinking = Thinking(
        enabled = this.mode,
        tokenBudget = this.tokenBudget,
        extractThinking = true,
        includedTags = this.includedTags,
        excludedTags = this.excludedTags,
        injectSystemPrompt = this.injectSystemPrompt,
        optOut = disabled,
    )

    /**
     * Configure thinking token budget.
     */
    fun applyTokenBudget(tokenBudget: Int): Thinking = Thinking(
        enabled = true,
        tokenBudget = tokenBudget,
        extractThinking = this.extractThinking,
        includedTags = this.includedTags,
        excludedTags = this.excludedTags,
        injectSystemPrompt = this.injectSystemPrompt,
    )

    /**
     * Control whether a system prompt hint is injected when [includedTags] is set.
     */
    fun withInjectSystemPrompt(inject: Boolean): Thinking = Thinking(
        enabled = this.mode,
        tokenBudget = this.tokenBudget,
        extractThinking = this.extractThinking,
        includedTags = this.includedTags,
        excludedTags = this.excludedTags,
        injectSystemPrompt = inject,
        optOut = disabled,
    )

    /**
     * True when this thinking chooses neither a mode nor a budget, so a role's [ReasoningEffort] may
     * apply. False after [NONE], even with extraction applied on top.
     */
    internal fun inheritsEffort(): Boolean = mode == null && tokenBudget == null
}

/**
 * How hard a reasoning model should think, whatever the provider. Set it with
 * [LlmOptions.withReasoningEffort]; provider integrations translate it:
 *
 * - OpenAI sends [value] as-is: `reasoning.effort` on the Responses API, `reasoning_effort` on Chat
 *   Completions. A model that does not offer a level rejects it. `withOpenAiReasoningEffort` takes
 *   precedence when both are set.
 * - Anthropic sends low to max as `output_config.effort` and leaves thinking at the model's default
 *   unless [Thinking] says otherwise; the effort takes precedence over the effort a
 *   [Thinking.withTokenBudget] budget maps to. A model that does not offer a level rejects it.
 *   [NONE] turns thinking off where the model catalog declares it can be, and fails as unsupported elsewhere.
 * - Other providers ignore it.
 */
enum class ReasoningEffort {
    NONE, LOW, MEDIUM, HIGH, XHIGH, MAX;

    /** The level as providers name it on the wire, e.g. `low`. */
    val value: String get() = name.lowercase()
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
    /**
     * Provider-specific extensions for features not common across all LLMs.
     * Allows provider-specific modules to add their own configuration without
     * coupling the core LlmOptions to provider-specific dependencies.
     * Internal field - use provider-specific extension functions to interact with it.
     */
    internal val extensions: Map<String, Any> = emptyMap(),
    /**
     * Provider-neutral reasoning effort; see [ReasoningEffort] for how each provider sends it.
     * Last, so that the overloads without it keep the JVM signatures callers compiled before it link to.
     */
    var reasoningEffort: ReasoningEffort? = null,
) : LlmHyperparameters, HasInfoString {

    /**
     * Binary compatibility: the synthetic constructor that Kotlin callers compiled before [reasoningEffort]
     * link to when they leave arguments out. Every omitted argument arrives as null, which is its default
     * except for [extensions].
     */
    @Suppress("UNUSED_PARAMETER")
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(
        modelSelectionCriteria: ModelSelectionCriteria?, model: String?, role: String?, temperature: Double?,
        frequencyPenalty: Double?, maxTokens: Int?, presencePenalty: Double?, topK: Int?, topP: Double?,
        thinking: Thinking?, timeout: Duration?, extensions: Map<String, Any>?, mask: Int, marker: DefaultConstructorMarker?,
    ) : this(
        modelSelectionCriteria, model, role, temperature, frequencyPenalty, maxTokens, presencePenalty, topK, topP,
        thinking, timeout, extensions ?: emptyMap(),
    )

    /**
     * Source and binary compatibility: the twelve-argument `copy` that Java and positional Kotlin callers
     * written before [reasoningEffort] call. It keeps the effort. It has no default values, so Kotlin
     * named-argument calls such as `copy(temperature = 0.3)` still resolve only to the data-class `copy`;
     * the old `copy$default` is [Companion.copyDefault].
     */
    @Deprecated("Use the copy that takes reasoningEffort, or named arguments")
    fun copy(
        modelSelectionCriteria: ModelSelectionCriteria?,
        model: String?,
        role: String?,
        temperature: Double?,
        frequencyPenalty: Double?,
        maxTokens: Int?,
        presencePenalty: Double?,
        topK: Int?,
        topP: Double?,
        thinking: Thinking?,
        timeout: Duration?,
        extensions: Map<String, Any>,
    ): LlmOptions = copy(
        modelSelectionCriteria, model, role, temperature, frequencyPenalty, maxTokens, presencePenalty, topK, topP,
        thinking, timeout, extensions, reasoningEffort,
    )

    @get:Schema(
        description = "If provided, custom selection criteria for the LLM to use. If not provided, a default LLM will be used.",
        required = false,
    )
    @get:JsonProperty(
        access = JsonProperty.Access.READ_ONLY
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

    /** Set the provider-neutral reasoning effort; see [ReasoningEffort] for how each provider sends it. */
    fun withReasoningEffort(reasoningEffort: ReasoningEffort): LlmOptions {
        return copy(reasoningEffort = reasoningEffort)
    }

    /**
     * The model these options name, however they name it.
     *
     * [withModel] records the choice as a [ByNameModelSelectionCriteria] while configuration
     * binding sets the [model] field, so neither one alone answers the question.
     */
    @get:JsonIgnore
    val modelName: String?
        get() = model ?: (criteria as? ByNameModelSelectionCriteria)?.name

    /**
     * Fill in anything this instance leaves unset from [defaults], keeping every value set here.
     *
     * Used to apply the hyperparameters configured against a role without overriding what the
     * caller asked for: a role may say `temperature: 0.3`, but a caller that passed its own
     * temperature still gets that one.
     *
     * Model selection is taken from [defaults] wholesale, since the point of resolving a role is
     * to decide which model it means.
     *
     * A caller that chose its own thinking mode or budget but no [reasoningEffort] does not inherit the
     * effort from [defaults], since the effort would override that choice; extraction added after
     * [withoutThinking] keeps that opt-out. Extraction-only thinking chooses neither, so it inherits the effort.
     */
    fun withDefaultsFrom(defaults: LlmOptions): LlmOptions =
        copy(
            modelSelectionCriteria = defaults.modelSelectionCriteria,
            model = defaults.model,
            role = defaults.role,
            temperature = temperature ?: defaults.temperature,
            frequencyPenalty = frequencyPenalty ?: defaults.frequencyPenalty,
            maxTokens = maxTokens ?: defaults.maxTokens,
            presencePenalty = presencePenalty ?: defaults.presencePenalty,
            topK = topK ?: defaults.topK,
            topP = topP ?: defaults.topP,
            thinking = thinking ?: defaults.thinking,
            timeout = timeout ?: defaults.timeout,
            extensions = defaults.extensions + extensions,
            reasoningEffort = reasoningEffort ?: defaults.reasoningEffort.takeIf {
                thinking.let { it == null || it.inheritsEffort() }
            },
        )

    /**
     * Get a provider-specific extension value by key.
     * Returns null if the extension is not present or cannot be cast to type T.
     *
     * **Internal API**: For provider modules only. Application code should use
     * provider-specific extension functions (e.g., getAnthropicCaching()).
     *
     * @param key The extension key
     * @return The extension value or null
     */
    @InternalExtensionApi
    @Suppress("UNCHECKED_CAST")
    fun <T> getExtension(key: String): T? {
        return extensions[key] as? T
    }

    /**
     * Add or update a provider-specific extension.
     * Returns a new LlmOptions with the extension added/updated.
     *
     * **Internal API**: For provider modules only. Application code should use
     * provider-specific extension functions (e.g., withAnthropicCaching()).
     *
     * @param key The extension key
     * @param value The extension value
     * @return A new LlmOptions with the extension
     */
    @InternalExtensionApi
    fun withExtension(key: String, value: Any): LlmOptions {
        return copy(extensions = extensions + (key to value))
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return toString().indent(indent)
    }

    companion object {

        /**
         * Binary compatibility: the `copy$default` that Kotlin callers compiled before [reasoningEffort]
         * link to. Bit `i` of [mask] set means argument `i` was omitted, so [self]'s value is kept.
         */
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
        fun copyDefault(
            self: LlmOptions, modelSelectionCriteria: ModelSelectionCriteria?, model: String?, role: String?,
            temperature: Double?, frequencyPenalty: Double?, maxTokens: Int?, presencePenalty: Double?, topK: Int?,
            topP: Double?, thinking: Thinking?, timeout: Duration?, extensions: Map<String, Any>?, mask: Int, marker: Any?,
        ): LlmOptions {
            fun omitted(i: Int) = mask and (1 shl i) != 0
            return self.copy(
                modelSelectionCriteria = if (omitted(0)) self.modelSelectionCriteria else modelSelectionCriteria,
                model = if (omitted(1)) self.model else model,
                role = if (omitted(2)) self.role else role,
                temperature = if (omitted(3)) self.temperature else temperature,
                frequencyPenalty = if (omitted(4)) self.frequencyPenalty else frequencyPenalty,
                maxTokens = if (omitted(5)) self.maxTokens else maxTokens,
                presencePenalty = if (omitted(6)) self.presencePenalty else presencePenalty,
                topK = if (omitted(7)) self.topK else topK,
                topP = if (omitted(8)) self.topP else topP,
                thinking = if (omitted(9)) self.thinking else thinking,
                timeout = if (omitted(10)) self.timeout else timeout,
                extensions = if (omitted(11)) self.extensions else extensions ?: emptyMap(),
                reasoningEffort = self.reasoningEffort,
            )
        }

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
