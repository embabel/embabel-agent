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
package com.embabel.agent.api.common.support.streaming

import com.embabel.agent.api.common.streaming.StreamingPromptRunner
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import reactor.core.publisher.Flux

/**
 * Implementation of StreamingPromptRunner.Streaming that bridges the API layer to SPI layer.
 *
 * This class delegates streaming operations from the API layer (StreamingPromptRunner.Streaming)
 * to the SPI layer (StreamingLlmOperations), handling the conversion between API and SPI concerns.
 */
internal class StreamingImpl(
    private val streamingLlmOperations: StreamingLlmOperations,
    private val interaction: LlmInteraction,
    private val messages: List<Message>,
    private val agentProcess: AgentProcess,
    private val action: Action?,
) : StreamingPromptRunner.Streaming {

    /**
     * Create a copy of this instance with selective parameter changes.
     */
    private fun copy(
        streamingLlmOperations: StreamingLlmOperations = this.streamingLlmOperations,
        interaction: LlmInteraction = this.interaction,
        messages: List<Message> = this.messages,
        agentProcess: AgentProcess = this.agentProcess,
        action: Action? = this.action,
    ): StreamingImpl = StreamingImpl(
        streamingLlmOperations, interaction, messages, agentProcess, action
    )

    override fun withPrompt(prompt: String): StreamingPromptRunner.Streaming {
        return copy(messages = listOf(UserMessage(prompt)))
    }

    override fun withMessages(messages: List<Message>): StreamingPromptRunner.Streaming {
        return copy(messages = messages)
    }

    override fun generateStream(): Flux<String> {
        return streamingLlmOperations.generateStream(
            messages = messages,
            interaction = interaction,
            agentProcess = agentProcess,
            action = action
        )
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
