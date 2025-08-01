/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi

import com.embabel.agent.core.*
import com.embabel.agent.event.LlmRequestEvent
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptContributorConsumer
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indent
import org.springframework.ai.tool.ToolCallback

/**
 * All prompt interactions through the platform need a unique id
 * This allows LLM interactions to be optimized by an AgentPlatform
 */
@JvmInline
value class InteractionId(val value: String) {

    override fun toString(): String = value
}

/**
 * Spec for calling an LLM. Optional LlmOptions,
 * plus tool groups and prompt contributors.
 */
interface LlmUse : PromptContributorConsumer, ToolGroupConsumer {
    val llm: LlmOptions?

    /**
     * Whether to generate examples for the prompt.
     * Defaults to unknown: Set to false if generating your own examples.
     */
    val generateExamples: Boolean?

}

/**
 * Spec for calling an LLM. Optional LlmOptions,
 * plus tool callbacks and prompt contributors.
 */
interface LlmCall : LlmUse, ToolConsumer {

    val contextualPromptContributors: List<ContextualPromptElement>

    companion object {

        operator fun invoke(llm: LlmOptions? = null): LlmCall = LlmCallImpl(
            llm = llm,
            name = MobyNameGenerator.generateName(),
        )

        @JvmStatic
        fun using(
            llm: LlmOptions,
        ): LlmCall = invoke(llm = llm)

    }
}

private data class LlmCallImpl(
    override val name: String,
    override val llm: LlmOptions? = null,
    override val toolGroups: Set<ToolGroupRequirement> = emptySet(),
    override val toolCallbacks: List<ToolCallback> = emptyList(),
    override val promptContributors: List<PromptContributor> = emptyList(),
    override val contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
    override val generateExamples: Boolean = false,
) : LlmCall

/**
 * Encapsulates an interaction with an LLM.
 * An LlmInteraction is a specific instance of an LlmCall.
 * The LLM must have been chosen and the call has a unique identifier.
 * @param id Unique identifier for the interaction. Note that this is NOT
 * the id of this particular LLM call, but of the interaction in general.
 * For example, it might be the "analyzeProject" call within the "Analyze"
 * action. Every such call with have the same id, but many calls may be made
 * across different AgentProcesses, or even within the same AgentProcess
 * if the action can be rerun.
 * This is per action, not per process.
 * @param llm LLM options to use, specifying model and hyperparameters
 * @param toolCallbacks Tool callbacks to use for this interaction
 * @param promptContributors Prompt contributors to use for this interaction
 */
data class LlmInteraction(
    val id: InteractionId,
    override val llm: LlmOptions = LlmOptions(),
    override val toolGroups: Set<ToolGroupRequirement> = emptySet(),
    override val toolCallbacks: List<ToolCallback> = emptyList(),
    override val promptContributors: List<PromptContributor> = emptyList(),
    override val contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
    override val generateExamples: Boolean? = null,
) : LlmCall {

    override val name: String = id.value

    companion object {

        @JvmStatic
        fun from(llm: LlmCall, id: InteractionId) = LlmInteraction(
            id = id,
            llm = llm.llm ?: LlmOptions(),
            toolCallbacks = llm.toolCallbacks,
            toolGroups = llm.toolGroups,
            promptContributors = llm.promptContributors,
            generateExamples = llm.generateExamples,
        )

        @JvmStatic
        fun using(llm: LlmOptions) = from(
            llm = LlmCall.using(llm),
            id = InteractionId("using"),
        )
    }
}

/**
 * The LLM returned an object of the wrong type.
 */
class InvalidLlmReturnFormatException(
    val llmReturn: String,
    val expectedType: Class<*>,
    cause: Throwable,
) : RuntimeException(
    "Invalid LLM return when expecting ${expectedType.name}: Root cause=${cause.message}",
    cause,
),
    HasInfoString {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        if (verbose == true) {
            "${javaClass.simpleName}: Expected type: ${expectedType.name}, root cause: ${cause!!.message}, return\n$llmReturn".indent(indent)
        } else {
            "${javaClass.simpleName}: Expected type: ${expectedType.name}, root cause: ${cause!!.message}"
        }
}

/**
 * Wraps LLM operations.
 * All user-initiated LLM operations go through this,
 * allowing the AgentPlatform to mediate them.
 * This interface is not directly for use in user code. Prefer PromptRunner
 * An LlmOperations implementation is responsible for resolving all relevant
 * tool callbacks for the current AgentProcess (in addition to those passed in directly),
 * and emitting events.
 * @see com.embabel.agent.api.common.PromptRunner
 */
interface LlmOperations {

    /**
     * Generate text in the context of an AgentProcess.
     * @param prompt Prompt to generate text from
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     */
    fun generate(
        prompt: String,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): String

    /**
     * Create an output object, in the context of an AgentProcess.
     * @param prompt Function to generate the prompt from the input object
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param outputClass Class of the output object
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @throws InvalidLlmReturnFormatException if the LLM returns an object of the wrong type
     */
    fun <O> createObject(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O

    /**
     * Try to create an output object in the context of an AgentProcess.
     * Return a failure result if the LLM does not have enough information to create the object.
     * @param prompt User prompt
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param outputClass Class of the output object
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @throws InvalidLlmReturnFormatException if the LLM returns an object of the wrong type
     */
    fun <O> createObjectIfPossible(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<O>

    /**
     * Low level transform, not necessarily aware of platform
     * @param prompt user prompt
     * @param interaction The LLM call options
     * @param outputClass Class of the output object
     * @param llmRequestEvent Event already published for this request if one has been
     */
    fun <O> doTransform(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O

}
