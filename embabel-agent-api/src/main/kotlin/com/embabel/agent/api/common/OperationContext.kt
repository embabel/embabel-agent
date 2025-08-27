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

import com.embabel.agent.api.common.autonomy.AgentInvocation
import com.embabel.agent.api.common.support.OperationContextPromptRunner
import com.embabel.agent.api.dsl.AgentScopeBuilder
import com.embabel.agent.core.*
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.agent.rag.RagService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.prompt.CurrentDate
import com.embabel.common.ai.prompt.PromptContributor
import org.springframework.ai.embedding.EmbeddingModel
import java.util.concurrent.CompletableFuture

/**
 * Context for any operation. Exposes blackboard and process context.
 * @param processContext the process context
 */
interface OperationContext : Blackboard, ToolGroupConsumer {

    val processContext: ProcessContext

    val agentProcess: AgentProcess
        get() = processContext.agentProcess

    fun agentPlatform() = processContext.platformServices.agentPlatform

    /**
     * Action or operation that is being executed.
     */
    val operation: Operation

    /**
     * Any agents known to the present platform that can handle the given object and return the given result type.
     * It is not an error if there are no such agents
     */
    fun <T : Any> fireAgent(
        obj: Any,
        resultType: Class<T>,
    ): CompletableFuture<T>? {
        val invocation = AgentInvocation.create(agentPlatform(), resultType)
        return invocation.invokeAsync(obj)
    }

    /**
     * Get AI functionality for this context
     */
    fun ai(): Ai = OperationContextAi(this)

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
     * @param items the collection of items to process
     * @param maxConcurrency the maximum number of concurrent operations to run
     * @param transform the transformation function to apply to each element
     */
    fun <T, R> parallelMap(
        items: Collection<T>,
        maxConcurrency: Int,
        transform: (t: T) -> R,
    ): List<R> = processContext.platformServices.asyncer.parallelMap(
        items = items,
        transform = transform,
        maxConcurrency = maxConcurrency,
    )


    companion object {
        operator fun invoke(
            processContext: ProcessContext,
            operation: Operation,
            toolGroups: Set<ToolGroupRequirement>,
        ): OperationContext =
            OperationContextImpl(
                processContext = processContext,
                operation = operation,
                toolGroups = toolGroups,
            )
    }
}

private class OperationContextImpl(
    override val processContext: ProcessContext,
    override val operation: Operation,
    override val toolGroups: Set<ToolGroupRequirement>,
) : OperationContext, Blackboard by processContext.agentProcess {
    override fun toString(): String {
        return "${javaClass.simpleName}(processContext=$processContext, operation=${operation.name})"
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

class SupplierActionContext<O>(
    override val processContext: ProcessContext,
    override val action: Action,
    val outputClass: Class<O>,
) : ActionContext, Blackboard by processContext.agentProcess,
    AgenticEventListener by processContext {

    override val toolGroups: Set<ToolGroupRequirement>
        get() = action.toolGroups

    override val operation = action

    val inputs: List<Any> get() = emptyList()

    override fun domainObjectInstances(): List<Any> = listOf(outputClass)

}

private class OperationContextAi(
    private val context: OperationContext,
) : Ai {

    override fun withEmbeddingModel(criteria: ModelSelectionCriteria): EmbeddingModel {
        return context.processContext.platformServices.modelProvider().getEmbeddingService(
            criteria
        ).model
    }

    override fun withLlm(llm: LlmOptions): PromptRunner {
        return context.promptRunner().withLlm(llm)
    }

    override fun rag(): RagService {
        return context.processContext.platformServices.ragService
    }
}
