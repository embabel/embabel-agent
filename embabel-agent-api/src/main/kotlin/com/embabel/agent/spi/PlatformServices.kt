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
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.channel.OutputChannel
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.event.RagEventListener
import com.embabel.agent.rag.RagService
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Services used by the platform and available to user-authored code.
 */
interface PlatformServices {

    /**
     * The agent platform executing this agent
     */
    val agentPlatform: AgentPlatform

    /**
     * Operations to use for LLMs
     */
    val llmOperations: LlmOperations

    /**
     * Event listener for agentic events
     */
    val eventListener: AgenticEventListener

    /**
     * Operation scheduler for scheduling operations
     */
    val operationScheduler: OperationScheduler

    /**
     * Asyncer for async operations
     */
    val asyncer: Asyncer

    /**
     * Default RAG service
     */
    val ragService: RagService

    val objectMapper: ObjectMapper

    val outputChannel: OutputChannel
    val templateRenderer: TemplateRenderer

    fun autonomy(): Autonomy
    fun modelProvider(): ModelProvider

    /**
     * Create a RagService with the given name, or the default if no name is given.
     * Enhance if necessary
     */
    fun ragService(
        context: OperationContext,
        serviceName: String?,
        listener: RagEventListener,
    ): RagService?

    fun withEventListener(agenticEventListener: AgenticEventListener): PlatformServices
}
