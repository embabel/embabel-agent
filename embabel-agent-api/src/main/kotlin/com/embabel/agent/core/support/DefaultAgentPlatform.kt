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
package com.embabel.agent.core.support

import com.embabel.agent.core.*
import com.embabel.agent.event.AgentDeploymentEvent
import com.embabel.agent.event.AgentProcessCreationEvent
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.rag.RagService
import com.embabel.agent.spi.*
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.testing.DummyObjectCreatingLlmOperations
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
internal class DefaultAgentPlatform(
    @Value("\${embabel.agent-platform.name:default-agent-platform}")
    override val name: String,
    @Value("\${embabel.agent-platform.description:Default Agent Platform}")
    override val description: String,
    private val llmOperations: LlmOperations,
    override val toolGroupResolver: ToolGroupResolver,
    private val eventListener: AgenticEventListener,
    private val agentProcessIdGenerator: AgentProcessIdGenerator = AgentProcessIdGenerator.RANDOM,
    private val agentProcessRepository: AgentProcessRepository = InMemoryAgentProcessRepository(),
    private val operationScheduler: OperationScheduler = OperationScheduler.PRONTO,
    private val ragService: RagService,
) : AgentPlatform {

    private val logger = LoggerFactory.getLogger(DefaultAgentPlatform::class.java)

    private val yamlObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    private val agents: MutableMap<String, Agent> = ConcurrentHashMap()

    override val platformServices = PlatformServices(
        llmOperations = llmOperations,
        agentPlatform = this,
        eventListener = eventListener,
        operationScheduler = operationScheduler,
        ragService = ragService,
    )

    init {
        logger.debug("{}: event listener: {}", name, eventListener)
    }

    override fun getAgentProcess(id: String): AgentProcess? {
        return agentProcessRepository.findById(id)
    }

    override fun agents(): List<Agent> =
        agents.values.sortedBy { it.name }

    override fun deploy(agent: Agent): DefaultAgentPlatform {
        agents[agent.name] = agent
        logger.debug("✅ Deployed agent {}\n\tdescription: {}", agent.name, agent.description)
        eventListener.onPlatformEvent(AgentDeploymentEvent(this, agent))
        return this
    }

    fun deploy(resource: Resource): DefaultAgentPlatform {
        logger.info("Loading agent from {}", resource)
        val agent = yamlObjectMapper.readValue<Agent>(resource.inputStream, Agent::class.java)
        return deploy(agent)
    }


    /**
     * Deploy all agents from the given path
     */
    fun deployAgents(agentPath: String = "classpath:agents/*.yml"): DefaultAgentPlatform {
        val resolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources(agentPath)
        resources.map { resource ->
            deploy(resource)
        }
        return this
    }

    private fun createBlackboard(processOptions: ProcessOptions): Blackboard {
        if (processOptions.blackboard != null) {
            logger.info("Using existing blackboard {}", processOptions.blackboard.blackboardId)
            return processOptions.blackboard
        }
        return InMemoryBlackboard()
    }

    override fun runAgentFrom(
        agent: Agent,
        processOptions: ProcessOptions,
        bindings: Map<String, Any>,
    ): AgentProcess {
        val blackboard = createBlackboard(processOptions)
        blackboard.bindAll(bindings)
        val agentProcess = createAgentProcess(agent, processOptions, blackboard)
        return agentProcess.run()
    }

    private fun createAgentProcess(
        agent: Agent,
        processOptions: ProcessOptions,
        blackboard: Blackboard,
    ): AgentProcess {
        val platformServicesToUse = if (processOptions.test) {
            logger.warn("Using test LLM operations: {}", processOptions)
            platformServices.copy(llmOperations = DummyObjectCreatingLlmOperations.LoremIpsum)
        } else {
            platformServices
        }

        val agentProcess = SimpleAgentProcess(
            agent = agent,
            platformServices = platformServicesToUse,
            blackboard = blackboard,
            id = agentProcessIdGenerator.createProcessId(agent, processOptions),
            parentId = null,
            processOptions = processOptions,
        )
        logger.debug("🚀 Creating process {}", agentProcess.id)
        eventListener.onProcessEvent(AgentProcessCreationEvent(agentProcess))
        return agentProcess
    }

    override fun createChildProcess(
        agent: Agent,
        parentAgentProcess: AgentProcess
    ): AgentProcess {
        val childBlackboard = parentAgentProcess.processContext.blackboard.spawn()
        val processOptions = parentAgentProcess.processContext.processOptions
        val childAgentProcess = SimpleAgentProcess(
            agent = agent,
            platformServices = parentAgentProcess.processContext.platformServices,
            blackboard = childBlackboard,
            id = "${parentAgentProcess.agent.name} >> ${
                agentProcessIdGenerator.createProcessId(
                    agent,
                    processOptions,
                )
            }",
            parentId = parentAgentProcess.id,
            processOptions = processOptions,
        )
        logger.debug("👶 Creating child process {} from {}", childAgentProcess.id, parentAgentProcess.id)
        eventListener.onProcessEvent(AgentProcessCreationEvent(childAgentProcess))
        return childAgentProcess
    }
}
