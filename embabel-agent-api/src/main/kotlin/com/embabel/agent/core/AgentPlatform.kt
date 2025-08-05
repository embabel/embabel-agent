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
package com.embabel.agent.core

import com.embabel.agent.common.Constants
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.spi.ToolGroupResolver
import java.util.concurrent.CompletableFuture

/**
 * An AgentPlatform can run agents. It can also act as an agent itself,
 * drawing on all of its agents as its own actions, goals, and conditions.
 * An AgentPlatform is stateful, as agents can be deployed to it.
 * See TypedOps for a higher level API with typed I/O.
 */
interface AgentPlatform : AgentScope {

    val platformServices: PlatformServices

    val toolGroupResolver: ToolGroupResolver

    /**
     * Find an agent process by id. Implementations are only obliged to
     * resolve running processes, although they may choose to return older processes.
     */
    fun getAgentProcess(id: String): AgentProcess?

    /**
     * Kill an agent process by id.
     * Return the killed process, or null if no such process was found.
     */
    fun killAgentProcess(id: String): AgentProcess?

    fun agents(): List<Agent>

    fun deploy(agent: Agent): AgentPlatform

    fun deploy(agentScope: AgentScope): AgentPlatform {
        if (agentScope is Agent) {
            return deploy(agentScope)
        }
        deploy(
            Agent(
                name = agentScope.name,
                provider = Constants.EMBABEL_PROVIDER,
                description = agentScope.name,
                actions = agentScope.actions,
                goals = agentScope.goals,
                conditions = agentScope.conditions,
            )
        )
        return this
    }

    /**
     * Run the agent from the given ProcessOptions.
     * We might create a new blackboard or have one
     * @param agent the agent to run. Does not need to be deployed to the platform
     * @param processOptions the options for the process
     * @param bindings the bindings for the process: Objects that are pre-bound
     * to the blackboard.
     */
    @Deprecated("Use createAgentProcess and run or start instead")
    fun runAgentFrom(
        agent: Agent,
        processOptions: ProcessOptions = ProcessOptions(),
        bindings: Map<String, Any>,
    ): AgentProcess

    /**
     * Run the given agent with the given input, which will be added
     * to the blackboard with the default binding.
     * @param agent the agent to run. Does not need to be deployed to the platform
     * @param processOptions the options for the process
     * @param input the input to bind to the blackboard
     */
    @Deprecated("Use createAgentProcess and run or start instead")
    fun runAgentWithInput(
        agent: Agent,
        processOptions: ProcessOptions = ProcessOptions(),
        input: Any,
    ): AgentProcess = runAgentFrom(
        agent,
        processOptions,
        mapOf(IoBinding.DEFAULT_BINDING to input),
    )

    /**
     * Create an agent process with the given options and bindings.
     * The process will not be started automatically, so this will return quickly,
     * unlike the run method on the created process.
     * AgentProcess status will be NOT_STARTED.
     * @param agent the agent to run. Does not need to be deployed to the platform
     * @param processOptions the options for the process
     * @param bindings the bindings for the process: Objects that are pre-bound
     * to the blackboard.
     */
    fun createAgentProcess(
        agent: Agent,
        processOptions: ProcessOptions,
        bindings: Map<String, Any>,
    ): AgentProcess

    /**
     * Create an agent process with the given options and bind all arguments.
     * @param agent the agent to run. Does not need to be deployed to the platform
     * @param processOptions the options for the process
     * @param objectsToAdd the objects to add to the blackboard.
     */
    fun createAgentProcessFrom(
        agent: Agent,
        processOptions: ProcessOptions,
        vararg objectsToAdd: Any,
    ): AgentProcess = createAgentProcess(
        agent,
        processOptions,
        emptyMap(),
    ).apply {
        objectsToAdd.forEach { addObject(it) }
    }

    /**
     * Run the given agent process in the background
     */
    fun start(
        agentProcess: AgentProcess,
    ): CompletableFuture<AgentProcess> {
        return platformServices.asyncer.async {
            agentProcess.run()
        }
    }

    fun createChildProcess(
        agent: Agent,
        parentAgentProcess: AgentProcess,
    ): AgentProcess

    override val domainTypes: Collection<DomainType>
        get() = agents().flatMap { it.domainTypes }.distinctBy { it.name }

    override val actions: List<Action>
        get() = agents().flatMap { it.actions }.distinctBy { it.name }

    override val goals: Set<Goal>
        get() = agents().flatMap { it.goals }.distinctBy { it.name }.toSet()

    override val conditions: Set<Condition>
        get() = agents().flatMap { it.conditions }.distinctBy { it.name }.toSet()

}
