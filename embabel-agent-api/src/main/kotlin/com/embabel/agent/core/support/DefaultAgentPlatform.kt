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

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.event.AgentDeploymentEvent
import com.embabel.agent.api.event.AgentProcessCreationEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.*
import com.embabel.agent.core.expression.LogicalExpressionParser
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.*
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.spi.support.InMemoryContextRepository
import com.embabel.agent.spi.support.SpringContextPlatformServices
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
open class DefaultAgentPlatform(
    @param:Value("\${embabel.agent.platform.name:default-agent-platform}")
    override val name: String,
    @param:Value("\${embabel.agent.platform.description:Default Agent Platform}")
    override val description: String,
    @param:Value("\${embabel.agent.platform.process-type:SIMPLE}")
    val processType: AgentPlatformProperties.ProcessType,
    private val llmOperations: LlmOperations,
    override val toolGroupResolver: ToolGroupResolver,
    private val eventListener: AgenticEventListener,
    private val agentProcessIdGenerator: AgentProcessIdGenerator = AgentProcessIdGenerator.RANDOM,
    private val contextRepository: ContextRepository = InMemoryContextRepository(),
    private val agentProcessRepository: AgentProcessRepository = InMemoryAgentProcessRepository(),
    private val operationScheduler: OperationScheduler = OperationScheduler.PRONTO,
    private val blackboardProvider: BlackboardProvider = InMemoryBlackboardProvider,
    private val asyncer: Asyncer,
    @param:Qualifier("embabelJacksonObjectMapper")
    private val objectMapper: ObjectMapper,
    private val outputChannel: OutputChannel,
    private val templateRenderer: TemplateRenderer,
    customLogicalExpressionParser: LogicalExpressionParser? = null,
    private val applicationContext: ApplicationContext? = null,
) : AgentPlatform {

    @Autowired(required = false)
    private var callbacks: List<AgentProcessCallback> = emptyList()

    private val logger = LoggerFactory.getLogger(DefaultAgentPlatform::class.java)

    private val plannerFactory: PlannerFactory = DefaultPlannerFactory

    private val agents: MutableMap<String, Agent> = ConcurrentHashMap()

    override val platformServices = SpringContextPlatformServices(
        llmOperations = llmOperations,
        agentPlatform = this,
        eventListener = eventListener,
        operationScheduler = operationScheduler,
        asyncer = asyncer,
        objectMapper = objectMapper,
        applicationContext = applicationContext,
        outputChannel = outputChannel,
        templateRenderer = templateRenderer,
        agentProcessRepository = agentProcessRepository,
        customLogicalExpressionParser = customLogicalExpressionParser,
    )

    init {
        logger.debug("{}: event listener: {}", name, eventListener)
    }

    override val opaque = false

    override fun getAgentProcess(id: String): AgentProcess? {
        return agentProcessRepository.findById(id)
    }

    override fun killAgentProcess(id: String): AgentProcess? {
        val process = agentProcessRepository.findById(id)
        if (process == null) {
            logger.warn("Agent process {} not found", id)
            return null
        }
        logger.info("Killing agent process {}", id)
        val killEvent = process.kill()
        if (killEvent != null) {
            eventListener.onProcessEvent(killEvent)
        } else {
            logger.warn("Failed to kill agent process {}", id)
        }
        return process
    }

    override fun agents(): List<Agent> =
        agents.values.sortedBy { it.name }

    override fun deploy(agent: Agent): DefaultAgentPlatform {
        agents[agent.name] = agent
        logger.debug("✅ Deployed agent {}\n\tdescription: {}", agent.name, agent.description)
        eventListener.onPlatformEvent(AgentDeploymentEvent(this, agent))
        return this
    }

    private fun createBlackboard(
        processOptions: ProcessOptions,
        processId: String,
    ): Blackboard {
        val blackboard = if (processOptions.blackboard != null) {
            logger.info(
                "Using existing blackboard {} for agent process {}",
                processOptions.blackboard.blackboardId,
                processId,
            )
            processOptions.blackboard
        } else {
            blackboardProvider.createBlackboard()
        }
        if (processOptions.contextId != null) {
            val context = contextRepository.findById(processOptions.contextId.value)
            if (context != null) {
                logger.info(
                    "Using existing context {} for agent process {}",
                    context.id,
                    processId,
                )
                context.populate(blackboard)
            } else {
                logger.warn(
                    "Context {} not found for agent process {}",
                    processOptions.contextId,
                    processId,
                )
            }
        }
        return blackboard
    }

    override fun runAgentFrom(
        agent: Agent,
        processOptions: ProcessOptions,
        bindings: Map<String, Any>,
    ): AgentProcess {
        val agentProcess = createAgentProcess(agent, processOptions, bindings)
        return agentProcess.run()
    }

    override fun createAgentProcess(
        agent: Agent,
        processOptions: ProcessOptions,
        bindings: Map<String, Any>,
    ): AgentProcess {
        val id = agentProcessIdGenerator.createProcessId(agent, processOptions)
        val blackboard = createBlackboard(processOptions, id)
        blackboard.bindAll(bindings)

        val agentProcess = when (processType) {
            AgentPlatformProperties.ProcessType.SIMPLE -> SimpleAgentProcess(
                agent = agent,
                platformServices = platformServices,
                blackboard = blackboard,
                id = id,
                parentId = null,
                processOptions = processOptions,
                plannerFactory = plannerFactory,
            )

            AgentPlatformProperties.ProcessType.CONCURRENT -> ConcurrentAgentProcess(
                agent = agent,
                platformServices = platformServices,
                blackboard = blackboard,
                id = id,
                parentId = null,
                processOptions = processOptions,
                plannerFactory = plannerFactory,
                callbacks = callbacks,
            )
        }
        logger.debug("🚀 Creating process {}", agentProcess.id)
        agentProcessRepository.save(agentProcess)
        eventListener.onProcessEvent(AgentProcessCreationEvent(agentProcess))
        return agentProcess
    }

    override fun createChildProcess(
        agent: Agent,
        parentAgentProcess: AgentProcess,
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
            plannerFactory = plannerFactory,
        )
        logger.debug("👶 Creating child process {} from {}", childAgentProcess.id, parentAgentProcess.id)
        agentProcessRepository.save(childAgentProcess)
        eventListener.onProcessEvent(AgentProcessCreationEvent(childAgentProcess))
        return childAgentProcess
    }
}
