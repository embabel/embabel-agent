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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.ContextualPromptElement
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolConsumer
import com.embabel.agent.core.ToolGroupConsumer
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptContributorConsumer
import com.fasterxml.jackson.annotation.JsonIgnore
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indent
import jakarta.validation.ConstraintViolation
import java.util.function.Predicate

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

    /**
     * Filter that determines which properties to include when creating objects.
     */
    val propertyFilter: Predicate<String>

    /**
     * Whether to validate generated objects.
     * Defaults to `true`; set to `false` to skip validation.
     */
    val validation: Boolean

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
    override val tools: List<Tool> = emptyList(),
    override val promptContributors: List<PromptContributor> = emptyList(),
    override val contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
    override val generateExamples: Boolean = false,
    override val propertyFilter: Predicate<String> = Predicate { true },
    override val validation: Boolean = true,
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
 * @param tools Tools to use for this interaction
 * @param promptContributors Prompt contributors to use for this interaction
 * @param useEmbabelToolLoop If true, use Embabel's own tool loop instead of Spring AI's.
 * This enables dynamic tool injection and gives full control over the tool execution loop.
 * Default is true.
 * @param maxToolIterations Maximum number of tool loop iterations (default 20)
 */
data class LlmInteraction(
    val id: InteractionId,
    override val llm: LlmOptions = LlmOptions(),
    override val toolGroups: Set<ToolGroupRequirement> = emptySet(),
    override val tools: List<Tool> = emptyList(),
    override val promptContributors: List<PromptContributor> = emptyList(),
    override val contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
    override val generateExamples: Boolean? = null,
    override val propertyFilter: Predicate<String> = Predicate { true },
    override val validation: Boolean = true,
    val useEmbabelToolLoop: Boolean = true,
    val maxToolIterations: Int = 20,
    val guardRails: List<com.embabel.agent.api.validation.guardrails.GuardRail> = emptyList(),
    val additionalInjectionStrategies: List<ToolInjectionStrategy> = emptyList(),
) : LlmCall {

    override val name: String = id.value

    /**
     * Get the interaction ID as a String.
     * Provided for Java compatibility since value classes don't generate standard getters.
     */
    @JsonIgnore
    fun getId(): String = id.value

    companion object {

        @JvmStatic
        fun from(
            llm: LlmCall,
            id: InteractionId,
        ) = LlmInteraction(
            id = id,
            llm = llm.llm ?: LlmOptions(),
            tools = llm.tools,
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
            "${javaClass.simpleName}: Expected type: ${expectedType.name}, root cause: ${cause!!.message}, return\n$llmReturn".indent(
                indent
            )
        } else {
            "${javaClass.simpleName}: Expected type: ${expectedType.name}, root cause: ${cause!!.message}"
        }
}

/**
 * Thrown the LLM returned an object that fails validation,
 * and although we tried, we could not correct it.
 */
class InvalidLlmReturnTypeException(
    val returnedObject: Any,
    val constraintViolations: Set<ConstraintViolation<*>>,
) : RuntimeException(
    "Validation errors: ${constraintViolations.joinToString(", ")}",
)
