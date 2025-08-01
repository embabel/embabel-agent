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

import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.dsl.AgentScopeBuilder
import com.embabel.agent.core.*
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.CurrentDate
import com.embabel.common.ai.prompt.PromptContributor

/**
 * Context for any operation. Exposes blackboard and process context.
 * @param processContext the process context
 */
interface OperationContext : Blackboard, ToolGroupConsumer {

    @Deprecated(
        "Avoid use in user code. Use agentProcess instead.",
    )
    val processContext: ProcessContext

    val agentProcess: AgentProcess
        get() = processContext.agentProcess

    fun agentPlatform() = processContext.platformServices.agentPlatform

    /**
     * Action or operation that is being executed.
     */
    val operation: Operation

    /**
     * Create a prompt runner for this context.
     * Application code should always go through this method to run LLM operations.
     * @param llm the LLM options to use
     * @param toolGroups extra local tool groups to use, in addition to those declared on the action if
     * we're in an action
     * @param promptContributors extra prompt contributors to use, in addition to those declared on the action if
     * we're in an action, or at agent level
     */
    fun promptRunner(
        llm: LlmOptions = LlmOptions(),
        toolGroups: Set<ToolGroupRequirement> = emptySet(),
        toolObjects: List<ToolObject> = emptyList(),
        promptContributors: List<PromptContributor> = emptyList(),
        contextualPromptContributors: List<ContextualPromptElement> = emptyList(),
        generateExamples: Boolean = false,
    ): PromptRunner {
        val promptContributorsToUse = (promptContributors + CurrentDate()).distinctBy { it.promptContribution().role }
        return OperationContextPromptRunner(
            context = this,
            llm = llm,
            toolGroups = toolGroups,
            toolObjects = toolObjects,
            promptContributors = promptContributorsToUse,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    /**
     * Create a prompt runner for this context
     * that can be customized later.
     * Principally for use from Java.
     */
    fun promptRunner(): PromptRunner = promptRunner(
        llm = LlmOptions(),
    )

    /**
     * Execute the operations in parallel.
     * @param coll the collection of elements to process
     * @param maxConcurrency the maximum number of concurrent operations to run
     * @param transform the transformation function to apply to each element
     */
    fun <T, R> parallelMap(
        coll: Collection<T>,
        maxConcurrency: Int,
        transform: (t: T) -> R,
    ): List<R> = processContext.platformServices.asyncer.parallelMap(
        coll = coll,
        transform = transform,
        maxConcurrency = maxConcurrency,
    )


    companion object {
        operator fun invoke(
            processContext: ProcessContext,
            operation: Operation,
            toolGroups: Set<ToolGroupRequirement>,
        ): OperationContext =
            MinimalOperationContext(
                processContext = processContext,
                operation = operation,
                toolGroups = toolGroups,
            )
    }
}

private class MinimalOperationContext(
    override val processContext: ProcessContext,
    override val operation: Operation,
    override val toolGroups: Set<ToolGroupRequirement>,
) : OperationContext, Blackboard by processContext.agentProcess {
    override fun toString(): String {
        return "MinimalOperationContext(processContext=$processContext, operation=${operation.name})"
    }
}

/**
 * Context for actions
 * @param processContext the process context
 * @param action the action being executed, if one is executing.
 * This is useful for getting tools etc.
 */
interface ActionContext : OperationContext {
    override val processContext: ProcessContext
    val action: Action?

    override fun promptRunner(
        llm: LlmOptions,
        toolGroups: Set<ToolGroupRequirement>,
        toolObjects: List<ToolObject>,
        promptContributors: List<PromptContributor>,
        contextualPromptContributors: List<ContextualPromptElement>,
        generateExamples: Boolean,
    ): PromptRunner {
        val promptContributorsToUse = (promptContributors + CurrentDate()).distinctBy { it.promptContribution().role }

        val doi = domainObjectInstances()
        return OperationContextPromptRunner(
            this,
            llm = llm,
            toolGroups = this.toolGroups + toolGroups,
            toolObjects = (toolObjects + doi.map { ToolObject(it) }).distinct(),
            promptContributors = promptContributorsToUse,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    /**
     * Return the domain object instances that are relevant for this action context.
     * They may expose tools.
     */
    fun domainObjectInstances(): List<Any>

    /**
     * Run the given agent as a sub-process of this action context.
     * @param outputClass the class of the output of the agent
     * @param agentScopeBuilder the builder for the agent scope to run
     */
    fun <O : Any> asSubProcess(
        outputClass: Class<O>,
        agentScopeBuilder: AgentScopeBuilder<O>,
    ): O {
        val agent = agentScopeBuilder.build().createAgent(
            name = agentScopeBuilder.name,
            provider = agentScopeBuilder.provider,
            description = agentScopeBuilder.name,
        )
        return asSubProcess(
            outputClass = outputClass,
            agent = agent,
        )
    }

    /**
     * Run the given agent as a sub-process of this action context.
     */
    fun <O : Any> asSubProcess(
        outputClass: Class<O>,
        agent: Agent,
    ): O {
        val singleAction = agentTransformer(
            agent = agent,
            inputClass = Unit::class.java,
            outputClass = outputClass,
        )

        singleAction.execute(
            processContext = this.processContext,
            action = action!!,
        )
        return last(outputClass) ?: throw IllegalStateException(
            "No output of type ${outputClass.name} found in context"
        )
    }

}

/**
 * Run the given agent as a sub-process of this action context.
 */
inline fun <reified O : Any> ActionContext.asSubProcess(
    agentScopeBuilder: AgentScopeBuilder<O>,
): O = asSubProcess(
    outputClass = O::class.java,
    agentScopeBuilder = agentScopeBuilder,
)

/**
 * Run the given agent as a sub-process of this action context.
 * @param agent the agent to run
 */
inline fun <reified O : Any> ActionContext.asSubProcess(
    agent: Agent,
): O = asSubProcess(
    outputClass = O::class.java,
    agent = agent,
)

/**
 * ActionContext with multiple inputs
 */
interface InputsActionContext : ActionContext {
    val inputs: List<Any>

    override fun domainObjectInstances(): List<Any> = inputs.flatMap { input ->
        when (input) {
            is List<*> -> input.filterNotNull()
            else -> listOf(input)
        }.distinct()
    }
}

/**
 * ActionContext with a single input
 */
interface InputActionContext<I> : InputsActionContext {
    val input: I

    override val inputs: List<Any> get() = listOfNotNull(input)
}

data class TransformationActionContext<I, O>(
    override val input: I,
    override val processContext: ProcessContext,
    override val action: Action,
    val inputClass: Class<I>,
    val outputClass: Class<O>,
) : InputActionContext<I>, Blackboard by processContext.agentProcess,
    AgenticEventListener by processContext {

    override val toolGroups: Set<ToolGroupRequirement>
        get() = action.toolGroups

    override val operation = action
}
