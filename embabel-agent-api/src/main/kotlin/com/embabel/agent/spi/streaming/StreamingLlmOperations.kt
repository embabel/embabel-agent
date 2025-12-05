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
package com.embabel.agent.spi.streaming

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.LlmInteraction
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import reactor.core.publisher.Flux

/**
 * Streaming extension of LlmOperations for real-time LLM response processing.
 *
 * This SPI interface provides reactive streaming capabilities that support
 * the API layer StreamingPromptRunner interfaces, enabling:
 * - Real-time processing of LLM responses as they arrive
 * - Streaming lists of objects from JSONL responses
 * - Mixed content streams with both objects and LLM reasoning (thinking)
 * - Progressive agent progress monitoring
 *
 * All streaming methods return Project Reactor Flux streams for integration
 * with Spring WebFlux and other reactive frameworks.
 */
interface StreamingLlmOperations {

    /**
     * Generate streaming text in the context of an AgentProcess.
     * Returns a Flux that emits text chunks as they arrive from the LLM.
     *
     * @param prompt Prompt to generate text from
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @return Flux of text chunks as they arrive from the LLM
     */
    fun generateStream(
        prompt: String,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<String> = generateStream(
        messages = listOf(UserMessage(prompt)),
        interaction = interaction,
        agentProcess = agentProcess,
        action = action,
    )

    /**
     * Generate streaming text from messages in the context of an AgentProcess.
     * Returns a Flux that emits text chunks as they arrive from the LLM.
     *
     * @param messages messages in the conversation so far
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @return Flux of text chunks as they arrive from the LLM
     */
    fun generateStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<String>

    /**
     * Create a streaming list of objects from JSONL response in the context of an AgentProcess.
     * Each line in the LLM response should be a valid JSON object matching the output class.
     * Objects are emitted to the Flux as they are parsed from individual lines.
     *
     * Supports the API layer createObjectStream() method.
     *
     * @param messages messages in the conversation so far
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param outputClass Class of the output objects
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @return Flux of typed objects as they are parsed from the response
     */
    fun <O> createObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<O>

    /**
     * Try to create a streaming list of objects in the context of an AgentProcess.
     * Return a Flux that may error if the LLM does not have enough information to create objects.
     * Streaming equivalent of createObjectIfPossible().
     *
     * @param messages messages
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param outputClass Class of the output objects
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @return Flux of Result<O> objects, where each Result indicates success/failure for that object
     */
    fun <O> createObjectStreamIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<Result<O>>

    /**
     * Create a streaming list of objects with LLM thinking content from mixed JSONL response.
     * Supports both JSON object lines and //THINKING: lines in the LLM response.
     * Returns StreamingEvent objects that can contain either typed objects or thinking content.
     *
     * This enables real-time visibility into LLM reasoning process alongside structured results.
     * Supports the API layer createObjectStreamWithThinking() method.
     *
     * @param messages messages in the conversation so far
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param outputClass Class of the output objects
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @return Flux of StreamingEvent objects containing either objects or thinking content
     */
    fun <O> createObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Flux<StreamingEvent<O>>

    /**
     * Low level streaming transform, not necessarily aware of platform.
     * Streams text chunks as they arrive from the LLM without platform mediation.
     *
     * @param messages messages in the conversation so far
     * @param interaction The LLM call options
     * @param llmRequestEvent Event already published for this request if one has been
     * @return Flux of text chunks as they arrive from the LLM
     */
    fun doTransformStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        llmRequestEvent: LlmRequestEvent<String>?,
    ): Flux<String>

    /**
     * Low level object streaming transform, not necessarily aware of platform.
     * Streams typed objects as they are parsed from JSONL response.
     *
     * @param messages messages in the conversation so far
     * @param interaction The LLM call options
     * @param outputClass Class of the output objects
     * @param llmRequestEvent Event already published for this request if one has been
     * @return Flux of typed objects as they are parsed from the response
     */
    fun <O> doTransformObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Flux<O>

    /**
     * Low level mixed content streaming transform, not necessarily aware of platform.
     * Streams both typed objects and thinking content from mixed JSONL response.
     *
     * @param messages messages in the conversation so far
     * @param interaction The LLM call options
     * @param outputClass Class of the output objects
     * @param llmRequestEvent Event already published for this request if one has been
     * @return Flux of StreamingEvent objects containing either objects or thinking content
     */
    fun <O> doTransformObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Flux<StreamingEvent<O>>
}
