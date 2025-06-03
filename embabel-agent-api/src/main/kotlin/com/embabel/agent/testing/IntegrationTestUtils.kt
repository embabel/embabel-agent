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
package com.embabel.agent.testing

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.support.DefaultAgentPlatform
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.rag.RagService
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.agent.spi.support.RegistryToolGroupResolver

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
            llmOperations = llmOperations ?: DummyObjectCreatingLlmOperations.Companion.LoremIpsum,
            eventListener = AgenticEventListener.from(listOfNotNull(EventSavingAgenticEventListener(), listener)),
            toolGroupResolver = toolGroupResolver ?: RegistryToolGroupResolver("empty", emptyList()),
            ragService = ragService ?: RagService.empty(),
            name = "dummy-agent-platform",
            description = "Dummy Agent Platform for Integration Testing",
        )
    }

    @JvmStatic
    fun dummyPlatformServices(): PlatformServices {
        return PlatformServices(
            agentPlatform = dummyAgentPlatform(),
            llmOperations = DummyObjectCreatingLlmOperations.Companion.LoremIpsum,
            eventListener = EventSavingAgenticEventListener(),
            operationScheduler = OperationScheduler.PRONTO,
            ragService = RagService.empty(),
        )
    }

}
