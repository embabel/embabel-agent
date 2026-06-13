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
package com.embabel.agent.config.models.ocigenai

import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback

/**
 * Spring AI chat options for OCI Generative AI.
 */
class OciGenAiChatOptions internal constructor(
    private var model: String? = null,
    var compartmentId: String? = null,
    initialServingMode: OciGenAiServingMode? = null,
    var endpointId: String? = null,
    initialApiFormat: OciGenAiApiFormat? = null,
    private var maxTokens: Int? = null,
    private var temperature: Double? = null,
    private var topP: Double? = null,
    private var topK: Int? = null,
    private var frequencyPenalty: Double? = null,
    private var presencePenalty: Double? = null,
    private var stopSequences: List<String>? = null,
    private var toolCallbacks: List<ToolCallback> = emptyList(),
    private var toolNames: Set<String> = emptySet(),
    private var toolContext: Map<String, Any> = emptyMap(),
    private var internalToolExecutionEnabled: Boolean? = null,
) : ToolCallingChatOptions {

    private var servingModeExplicit: Boolean = initialServingMode != null
    private var apiFormatExplicit: Boolean = initialApiFormat != null

    var servingMode: OciGenAiServingMode = initialServingMode ?: OciGenAiServingMode.ON_DEMAND
        set(value) {
            field = value
            servingModeExplicit = true
        }

    var apiFormat: OciGenAiApiFormat = initialApiFormat ?: OciGenAiApiFormat.GENERIC
        set(value) {
            field = value
            apiFormatExplicit = true
        }

    override fun getModel(): String? = model

    fun setModel(model: String?) {
        this.model = model
    }

    override fun getFrequencyPenalty(): Double? = frequencyPenalty

    fun setFrequencyPenalty(frequencyPenalty: Double?) {
        this.frequencyPenalty = frequencyPenalty
    }

    override fun getMaxTokens(): Int? = maxTokens

    fun setMaxTokens(maxTokens: Int?) {
        this.maxTokens = maxTokens
    }

    override fun getPresencePenalty(): Double? = presencePenalty

    fun setPresencePenalty(presencePenalty: Double?) {
        this.presencePenalty = presencePenalty
    }

    override fun getStopSequences(): List<String>? = stopSequences

    fun setStopSequences(stopSequences: List<String>?) {
        this.stopSequences = stopSequences
    }

    override fun getTemperature(): Double? = temperature

    fun setTemperature(temperature: Double?) {
        this.temperature = temperature
    }

    override fun getTopK(): Int? = topK

    fun setTopK(topK: Int?) {
        this.topK = topK
    }

    override fun getTopP(): Double? = topP

    fun setTopP(topP: Double?) {
        this.topP = topP
    }

    override fun getToolCallbacks(): List<ToolCallback> = toolCallbacks

    fun setToolCallbacks(toolCallbacks: List<ToolCallback>) {
        this.toolCallbacks = toolCallbacks
    }

    override fun getToolNames(): Set<String> = toolNames

    fun setToolNames(toolNames: Set<String>) {
        this.toolNames = toolNames
    }

    override fun getInternalToolExecutionEnabled(): Boolean? = internalToolExecutionEnabled

    fun setInternalToolExecutionEnabled(internalToolExecutionEnabled: Boolean?) {
        this.internalToolExecutionEnabled = internalToolExecutionEnabled
    }

    override fun getToolContext(): Map<String, Any> = toolContext

    fun setToolContext(toolContext: Map<String, Any>) {
        this.toolContext = toolContext
    }

    /**
     * Spring AI 2.0 contract: return a builder seeded with this instance's state.
     * Preserves OCI-specific fields (compartmentId, servingMode, endpointId, apiFormat)
     * in addition to the standard chat/tool-calling options.
     */
    override fun mutate(): ToolCallingChatOptions.Builder<*> = Builder(copy<OciGenAiChatOptions>())

    fun merge(runtimeOptions: ChatOptions?): OciGenAiChatOptions {
        val merged = copy<OciGenAiChatOptions>()
        if (runtimeOptions == null) {
            return merged
        }
        runtimeOptions.model?.let { merged.setModel(it) }
        runtimeOptions.frequencyPenalty?.let { merged.setFrequencyPenalty(it) }
        runtimeOptions.maxTokens?.let { merged.setMaxTokens(it) }
        runtimeOptions.presencePenalty?.let { merged.setPresencePenalty(it) }
        runtimeOptions.stopSequences?.let { merged.setStopSequences(it) }
        runtimeOptions.temperature?.let { merged.setTemperature(it) }
        runtimeOptions.topK?.let { merged.setTopK(it) }
        runtimeOptions.topP?.let { merged.setTopP(it) }

        if (runtimeOptions is ToolCallingChatOptions) {
            setMergedToolCallbacks(merged, runtimeOptions)
        }
        if (runtimeOptions is OciGenAiChatOptions) {
            runtimeOptions.compartmentId?.let { merged.compartmentId = it }
            if (runtimeOptions.servingModeExplicit) {
                merged.servingMode = runtimeOptions.servingMode
            }
            runtimeOptions.endpointId?.let { merged.endpointId = it }
            if (runtimeOptions.apiFormatExplicit) {
                merged.apiFormat = runtimeOptions.apiFormat
            }
        }
        return merged
    }

    private fun setMergedToolCallbacks(
        merged: OciGenAiChatOptions,
        runtimeOptions: ToolCallingChatOptions,
    ) {
        if (runtimeOptions.toolCallbacks.isNotEmpty()) {
            merged.toolCallbacks = runtimeOptions.toolCallbacks
        }
        if (runtimeOptions.toolNames.isNotEmpty()) {
            merged.toolNames = runtimeOptions.toolNames
        }
        if (runtimeOptions.toolContext.isNotEmpty()) {
            merged.toolContext = merged.toolContext + runtimeOptions.toolContext
        }
        runtimeOptions.internalToolExecutionEnabled?.let { merged.internalToolExecutionEnabled = it }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ChatOptions> copy(): T =
        OciGenAiChatOptions(
            model = model,
            compartmentId = compartmentId,
            initialServingMode = if (servingModeExplicit) servingMode else null,
            endpointId = endpointId,
            initialApiFormat = if (apiFormatExplicit) apiFormat else null,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            topK = topK,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            stopSequences = stopSequences,
            toolCallbacks = toolCallbacks,
            toolNames = toolNames,
            toolContext = toolContext,
            internalToolExecutionEnabled = internalToolExecutionEnabled,
        ) as T

    companion object {
        fun builder() = Builder()
    }

    class Builder internal constructor(
        private var options: OciGenAiChatOptions = OciGenAiChatOptions(),
    ) : ToolCallingChatOptions.Builder<Builder> {

        // OCI-specific options (not part of the Spring AI builder contract)

        fun compartmentId(compartmentId: String?): Builder = apply { options.compartmentId = compartmentId }

        fun servingMode(servingMode: OciGenAiServingMode): Builder = apply { options.servingMode = servingMode }

        fun endpointId(endpointId: String?): Builder = apply { options.endpointId = endpointId }

        fun apiFormat(apiFormat: OciGenAiApiFormat): Builder = apply { options.apiFormat = apiFormat }

        // ChatOptions.Builder contract

        override fun model(model: String?): Builder = apply { options.setModel(model) }

        override fun frequencyPenalty(frequencyPenalty: Double?): Builder =
            apply { options.setFrequencyPenalty(frequencyPenalty) }

        override fun maxTokens(maxTokens: Int?): Builder = apply { options.setMaxTokens(maxTokens) }

        override fun presencePenalty(presencePenalty: Double?): Builder =
            apply { options.setPresencePenalty(presencePenalty) }

        override fun stopSequences(stopSequences: List<String>?): Builder =
            apply { options.setStopSequences(stopSequences) }

        override fun temperature(temperature: Double?): Builder = apply { options.setTemperature(temperature) }

        override fun topK(topK: Int?): Builder = apply { options.setTopK(topK) }

        override fun topP(topP: Double?): Builder = apply { options.setTopP(topP) }

        override fun combineWith(other: ChatOptions.Builder<*>): Builder =
            apply { options = options.merge(other.build()) }

        override fun clone(): Builder = Builder(options.copy())

        // ToolCallingChatOptions.Builder contract

        override fun toolCallbacks(toolCallbacks: List<ToolCallback>): Builder =
            apply { options.setToolCallbacks(toolCallbacks) }

        override fun toolCallbacks(vararg toolCallbacks: ToolCallback): Builder =
            apply { options.setToolCallbacks(toolCallbacks.toList()) }

        override fun toolNames(toolNames: Set<String>): Builder =
            apply { options.setToolNames(toolNames) }

        override fun toolNames(vararg toolNames: String): Builder =
            apply { options.setToolNames(toolNames.toSet()) }

        override fun internalToolExecutionEnabled(internalToolExecutionEnabled: Boolean?): Builder =
            apply { options.setInternalToolExecutionEnabled(internalToolExecutionEnabled) }

        override fun toolContext(toolContext: Map<String, Any>): Builder =
            apply { options.setToolContext(toolContext) }

        override fun toolContext(key: String, value: Any): Builder =
            apply { options.setToolContext(options.toolContext + (key to value)) }

        override fun build(): OciGenAiChatOptions = options.copy()
    }
}
