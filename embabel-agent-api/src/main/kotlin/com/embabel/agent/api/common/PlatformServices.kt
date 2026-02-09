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
package com.embabel.agent.api.common

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.expression.LogicalExpressionParser
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.OperationScheduler
import com.embabel.chat.ConversationFactoryProvider
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

    val agentProcessRepository: AgentProcessRepository

    /**
     * Asyncer for async operations
     */
    val asyncer: Asyncer

    val logicalExpressionParser: LogicalExpressionParser

    val objectMapper: ObjectMapper

    val outputChannel: OutputChannel

    val templateRenderer: TemplateRenderer

    fun autonomy(): Autonomy

    fun modelProvider(): ModelProvider

    /**
     * Get the conversation factory provider for resolving conversation factories by type.
     *
     * Requires `embabel-chat-store` on the classpath. Without it,
     * this method throws [org.springframework.beans.factory.NoSuchBeanDefinitionException].
     */
    fun conversationFactoryProvider(): ConversationFactoryProvider

    fun withEventListener(agenticEventListener: AgenticEventListener): PlatformServices
}
