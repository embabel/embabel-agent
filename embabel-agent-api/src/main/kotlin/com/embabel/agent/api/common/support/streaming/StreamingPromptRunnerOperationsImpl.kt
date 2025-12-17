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
package com.embabel.agent.api.common.support.streaming

import com.embabel.agent.api.LlmInteraction
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import reactor.core.publisher.Flux

/**
 * Implementation of StreamingPromptRunnerOperations that bridges the API layer to SPI layer.
 *
 * This class delegates streaming operations from the API layer (StreamingPromptRunnerOperations)
 * to the SPI layer (StreamingLlmOperations), handling the conversion between API and SPI concerns.
 */
internal class StreamingPromptRunnerOperationsImpl(
    private val streamingLlmOperations: StreamingLlmOperations,
    private val interaction: LlmInteraction,
    private val messages: List<Message>,
    private val agentProcess: AgentProcess,
    private val action: Action?,
) : StreamingPromptRunnerOperations {

    /**
     * Create a copy of this instance with selective parameter changes.
     */
    private fun copy(
        streamingLlmOperations: StreamingLlmOperations = this.streamingLlmOperations,
        interaction: LlmInteraction = this.interaction,
        messages: List<Message> = this.messages,
        agentProcess: AgentProcess = this.agentProcess,
        action: Action? = this.action,
    ): StreamingPromptRunnerOperationsImpl = StreamingPromptRunnerOperationsImpl(
        streamingLlmOperations, interaction, messages, agentProcess, action
    )

    override fun withPrompt(prompt: String): StreamingPromptRunnerOperations {
        return copy(messages = listOf(UserMessage(prompt)))
    }

    override fun withMessages(messages: List<Message>): StreamingPromptRunnerOperations {
        return copy(messages = messages)
    }

    override fun <T> createObjectStream(itemClass: Class<T>): Flux<T> {
        return streamingLlmOperations.createObjectStream(
            messages = messages,
            interaction = interaction,
            outputClass = itemClass,
            agentProcess = agentProcess,
            action = action,
        )
    }

    override fun <T> createObjectStreamWithThinking(itemClass: Class<T>): Flux<StreamingEvent<T>> {
        return streamingLlmOperations.createObjectStreamWithThinking(
            messages = messages,
            interaction = interaction,
            outputClass = itemClass,
            agentProcess = agentProcess,
            action = action,
        )
    }
}
