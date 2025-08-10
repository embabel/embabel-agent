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
package com.embabel.agent.api.common

import com.embabel.agent.api.annotation.support.AgenticInfo
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.agent.spi.LlmCall
import com.embabel.agent.spi.LlmUse
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor

/**
 * User-facing interface for executing prompts.
 */
interface PromptRunnerOperations {

    /**
     * Generate text
     */
    infix fun generateText(prompt: String): String =
        createObject(
            prompt = prompt,
            outputClass = String::class.java,
        )

    /**
     * Create an object of the given type using the given prompt and LLM options from context
     * (process context or implementing class).
     * Prompts are typically created within the scope of an
     * @Action method that provides access to
     * domain object instances, offering type safety.
     */
    fun <T> createObject(
        prompt: String,
        outputClass: Class<T>,
    ): T

    /**
     * Try to create an object of the given type using the given prompt and LLM options from context
     * (process context or implementing class).
     * Prompt is typically created within the scope of an
     * @Action method that provides access to
     * domain object instances, offering type safety.
     */
    fun <T> createObjectIfPossible(
        prompt: String,
        outputClass: Class<T>,
    ): T?

    fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne = 0.8,
    ): Boolean

}

/**
 * Holds an annotated tool object.
 * Adds a naming strategy and a filter to the object.
 * @param obj the object the tool annotations are on
 * @param namingStrategy the naming strategy to use for the tool object's methods
 * @param filter a filter to apply to the tool object's methods
 */
data class ToolObject(
    val obj: Any,
    val namingStrategy: StringTransformer = StringTransformer.IDENTITY,
    val filter: (String) -> Boolean = { true },
) {

    init {
        if (obj is Iterable<*>) {
            throw IllegalArgumentException("Internal error: ToolObject cannot be an Iterable. Offending object: $obj")
        }
    }

    constructor (
        obj: Any,
    ) : this(
        obj = obj,
        namingStrategy = StringTransformer.IDENTITY,
        filter = { true },
    )

    fun withNamingStrategy(
        namingStrategy: StringTransformer,
    ): ToolObject = copy(namingStrategy = namingStrategy)

    fun withFilter(
        filter: (String) -> Boolean,
    ): ToolObject = copy(filter = filter)

    companion object {

        fun from(o: Any): ToolObject = o as? ToolObject
            ?: ToolObject(
                obj = o,
                namingStrategy = StringTransformer.IDENTITY,
                filter = { true },
            )

    }
}

/**
 * Define a handoff to a subagent.

 */
class Subagent private constructor(
    private val agentRef: Any,
    val inputClass: Class<*>,
) {

    /**
     * Subagent that is an agent
     * @param agent the subagent to hand off to
     * @param inputClass the class of the input that the subagent expects
     */
    constructor(
        agent: Agent,
        inputClass: Class<*>,
    ) : this(
        agentRef = agent,
        inputClass = inputClass,
    )

    constructor(
        agentName: String,
        inputClass: Class<*>,
    ) : this(
        agentRef = agentName,
        inputClass = inputClass,
    )

    /**
     * Reference to an annotated agent class.
     */
    constructor(
        agentType: Class<*>,
        inputClass: Class<*>,
    ) : this(
        agentRef = agentType,
        inputClass = inputClass,
    )

    fun resolve(agentPlatform: AgentPlatform): Agent {
        return when (agentRef) {
            is Agent -> agentRef
            is String -> agentPlatform.agents().find { it.name == agentRef }
                ?: throw IllegalArgumentException(
                    "Subagent with name '$agentRef' not found in platform ${agentPlatform.name}. " +
                            "Available agents: ${agentPlatform.agents().map { it.name }}"
                )

            is Class<*> -> {
                val agenticInfo = AgenticInfo(agentRef)
                if (!agenticInfo.agentic()) {
                    throw IllegalArgumentException(
                        "Subagent must be an Agent or a String representing the agent name, but was: $agentRef"
                    )
                }
                agentPlatform.agents().find { it.name == agenticInfo.agentName() }
                    ?: throw IllegalArgumentException(
                        "Subagent of type $agentRef with name '$agentRef' not found in platform ${agentPlatform.name}. " +
                                "Available agents: ${agentPlatform.agents().map { it.name }}"
                    )
            }

            else -> throw IllegalArgumentException(
                "Subagent must be an Agent or a String representing the agent name, but was: $agentRef"
            )
        }
    }
}


/**
 * User code should always use this interface to execute prompts.
 * Typically obtained from an [OperationContext] or [ActionContext] parameter,
 * via [OperationContext.ai]
 * A PromptRunner is immutable once constructed, and has determined
 * LLM and hyperparameters.
 * Thus, a PromptRunner can be reused within an action implementation.
 * A contextual facade to LlmOperations.
 * @see com.embabel.agent.spi.LlmOperations
 */
interface PromptRunner : LlmUse, PromptRunnerOperations {

    /**
     * Additional objects with @Tool annotation for use in this PromptRunner
     */
    val toolObjects: List<ToolObject>


    /**
     * Specify an LLM for the PromptRunner
     */
    fun withLlm(llm: LlmOptions): PromptRunner

    /**
     * Add a tool group to the PromptRunner
     * @param toolGroup name of the toolGroup we're requesting
     * @return PromptRunner instance with the added tool group
     */
    fun withToolGroup(toolGroup: String): PromptRunner =
        withToolGroup(ToolGroupRequirement(toolGroup))

    fun withToolGroups(toolGroups: Set<String>): PromptRunner =
        toolGroups.fold(this) { acc, toolGroup -> acc.withToolGroup(toolGroup) }

    /**
     * Add a set of tool groups to the PromptRunner
     * @param toolGroups the set of named tool groups to add
     */
    fun withTools(vararg toolGroups: String): PromptRunner =
        withToolGroups(toolGroups.toSet())

    fun withToolGroup(toolGroup: ToolGroupRequirement): PromptRunner

    /**
     * Add a tool object to the prompt runner.
     * @param toolObject the object to add. If it is null, nothing is done.
     * This is not an error
     * @return PromptRunner instance with the added tool object
     */
    fun withToolObject(toolObject: Any?): PromptRunner =
        if (toolObject == null) {
            this
        } else {
            withToolObject(ToolObject.from(toolObject))
        }

    /**
     * Add a tool object
     * @param toolObject the object to add.
     */
    fun withToolObject(
        toolObject: ToolObject,
    ): PromptRunner

    /**
     * Add a list of handoffs to agents on this platform
     * @param outputTypes the types of objects that can result from output flow
     */
    fun withHandoffs(
        vararg outputTypes: Class<*>,
    ): PromptRunner

    /**
     * Add a list of subagents to hand off to.
     */
    fun withSubagents(
        vararg subagents: Subagent,
    ): PromptRunner

    /**
     * Add a prompt contributor
     * @param promptContributor
     * @return PromptRunner instance with the added PromptContributor
     */
    fun withPromptContributor(promptContributor: PromptContributor): PromptRunner =
        withPromptContributors(listOf(promptContributor))

    fun withPromptContributors(promptContributors: List<PromptContributor>): PromptRunner

    /**
     * Add varargs of prompt contributors and contextual prompt elements.
     */
    fun withPromptElements(vararg elements: Any): PromptRunner {
        val promptContributors = elements.filterIsInstance<PromptContributor>()
        val contextualPromptElements = elements.filterIsInstance<ContextualPromptElement>()
        val oddOnesOut = elements.filterNot { it is PromptContributor || it is ContextualPromptElement }
        if (oddOnesOut.isNotEmpty()) {
            loggerFor<PromptRunner>().warn(
                "{} arguments to withPromptElements were not prompt contributors or contextual prompt elements and will be ignored: {}",
                oddOnesOut.size,
                oddOnesOut.joinToString(
                    ", ", prefix = "[", postfix = "]"
                )
            )
        }
        return withPromptContributors(promptContributors)
            .withContextualPromptContributors(contextualPromptElements)
    }

    /**
     * Add a prompt contributor that can see context
     */
    fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptRunner

    fun withContextualPromptContributor(
        contextualPromptContributor: ContextualPromptElement,
    ): PromptRunner =
        withContextualPromptContributors(listOf(contextualPromptContributor))

    /**
     * Set whether to generate examples of the output in the prompt
     * on a per-PromptRunner basis. This overides platform defaults.
     */
    fun withGenerateExamples(generateExamples: Boolean): PromptRunner

}

/**
 * Create an object of the given type
 */
inline infix fun <reified T> PromptRunner.createObject(prompt: String): T =
    createObject(prompt, T::class.java)

/**
 * Create an object of the given type.
 * Method overloading is evil
 **/
inline infix fun <reified T> PromptRunner.create(prompt: String): T =
    createObject(prompt, T::class.java)

inline fun <reified T> PromptRunner.createObjectIfPossible(prompt: String): T? =
    createObjectIfPossible(prompt, T::class.java)

interface LlmObjectCreationRequest : LlmCall {
    val requireResult: Boolean
    val outputClass: Class<*>
}

interface LlmCallRequest : LlmObjectCreationRequest {
    val prompt: String
}
