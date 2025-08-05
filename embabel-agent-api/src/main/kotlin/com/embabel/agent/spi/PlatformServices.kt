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
package com.embabel.agent.spi

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.channel.OutputChannel
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.rag.RagService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.ApplicationContext

/**
 * Services used by the platform and available to user-authored code.
 * @param agentPlatform agent platform executing this agent
 * @param llmOperations operations to use for LLMs
 * @param eventListener event listener for agentic events
 * @param operationScheduler operation scheduler for scheduling operations
 */
data class PlatformServices(
    val agentPlatform: AgentPlatform,
    val llmOperations: LlmOperations,
    val eventListener: AgenticEventListener,
    val operationScheduler: OperationScheduler,
    val asyncer: Asyncer,
    val ragService: RagService,
    val objectMapper: ObjectMapper,
    val outputChannel: OutputChannel,
    private val applicationContext: ApplicationContext?,
) {

    // We get this from the context because of circular dependencies
    fun autonomy(): Autonomy {
        if (applicationContext == null) {
            throw IllegalStateException("Application context is not available, cannot retrieve Autonomy bean.")
        }
        return applicationContext.getBean(Autonomy::class.java)
    }
}
