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
package com.embabel.agent.testing.integration

import com.embabel.agent.channel.DevNullOutputChannel
import com.embabel.agent.core.*
import com.embabel.agent.core.support.DefaultAgentPlatform
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.rag.RagService
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.RegistryToolGroupResolver
import com.embabel.agent.testing.common.EventSavingAgenticEventListener
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.Executors

object IntegrationTestUtils {
    /**
     * Create a dummy agent platform for integration testing.
     * The returned instance can be used to run agents.
     */
    @JvmStatic
    @JvmOverloads
    fun dummyAgentPlatform(
        llmOperations: LlmOperations? = null,
        listener: AgenticEventListener? = null,
        toolGroupResolver: ToolGroupResolver? = null,
        ragService: RagService? = null,
    ): AgentPlatform {
        return DefaultAgentPlatform(
            llmOperations = llmOperations ?: DummyObjectCreatingLlmOperations.LoremIpsum,
            eventListener = AgenticEventListener.Companion.from(
                listOfNotNull(
                    EventSavingAgenticEventListener(),
                    listener
                )
            ),
            toolGroupResolver = toolGroupResolver ?: RegistryToolGroupResolver("empty", emptyList()),
            ragService = ragService ?: RagService.Companion.empty(),
            name = "dummy-agent-platform",
            description = "Dummy Agent Platform for Integration Testing",
            asyncer = ExecutorAsyncer(Executors.newSingleThreadExecutor()),
            objectMapper = jacksonObjectMapper(),
            outputChannel = DevNullOutputChannel,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun dummyPlatformServices(eventListener: AgenticEventListener? = null): PlatformServices {
        return PlatformServices(
            agentPlatform = dummyAgentPlatform(),
            llmOperations = DummyObjectCreatingLlmOperations.LoremIpsum,
            eventListener = eventListener ?: EventSavingAgenticEventListener(),
            operationScheduler = OperationScheduler.PRONTO,
            ragService = RagService.empty(),
            asyncer = ExecutorAsyncer(Executors.newSingleThreadExecutor()),
            objectMapper = jacksonObjectMapper(),
            applicationContext = null,
            outputChannel = DevNullOutputChannel,
        )
    }

    @JvmStatic
    fun dummyAgentProcessRunning(
        agent: Agent,
        platformServices: PlatformServices? = null,
    ): AgentProcess {
        return SimpleAgentProcess(
            id = "dummy-agent-process",
            parentId = null,
            agent = agent,
            blackboard = InMemoryBlackboard(),
            processOptions = ProcessOptions(),
            platformServices = platformServices ?: dummyPlatformServices(),
        )
    }

    @JvmStatic
    fun dummyProcessContext(agent: Agent): ProcessContext {
        return ProcessContext(
            processOptions = ProcessOptions(),
            platformServices = dummyPlatformServices(),
            agentProcess = dummyAgentProcessRunning(agent),
            outputChannel = DevNullOutputChannel,
        )
    }

}
