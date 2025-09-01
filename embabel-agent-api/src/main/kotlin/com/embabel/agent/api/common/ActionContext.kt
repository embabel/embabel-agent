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
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.CurrentDate
import com.embabel.common.ai.prompt.PromptContributor

/**
 * OperationContext that execute actions.
 * An ExecutingOperationContext can execute agents as sub-processes.
 */
interface ExecutingOperationContext : OperationContext {

    /**
     * Run the given agent as a sub-process of this operation context.
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
     * Run the given agent as a sub-process of this operation context.
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
        )
        return last(outputClass) ?: throw IllegalStateException(
            "No output of type ${outputClass.name} found in context"
        )
    }

    companion object {

        /**
         * Create an ExecutingOperationContext for the given process context and operation.
         */
        operator fun invoke(
            name: String,
            agentProcess: AgentProcess,
        ): ExecutingOperationContext =
            ExecutingOperationContextImpl(
                processContext = agentProcess.processContext,
                operation = InjectedType.named(name),
                toolGroups = emptySet(),
            )
    }

}

private class ExecutingOperationContextImpl(
    override val processContext: ProcessContext,
    override val operation: Operation,
    override val toolGroups: Set<ToolGroupRequirement>,
) : ExecutingOperationContext, Blackboard by processContext.agentProcess {
    override fun toString(): String {
        return "${javaClass.simpleName}(processContext=$processContext, operation=${operation.name})"
    }
}

/**
 * Context for actions
 * @param processContext the process context
 * @param action the action being executed, if one is executing.
 * This is useful for getting tools etc.
 */
interface ActionContext : ExecutingOperationContext {
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

}
