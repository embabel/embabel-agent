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

package com.embabel.agent.core.internal

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import org.jetbrains.annotations.ApiStatus

/**
 * Wraps LLM operations.
 * All user-initiated LLM operations go through this,
 * allowing the AgentPlatform to mediate them.
 * This interface is not directly for use in user code. Prefer PromptRunner
 * An LlmOperations implementation is responsible for resolving all relevant
 * tool callbacks for the current AgentProcess (in addition to those passed in directly),
 * and emitting events.
 * @see com.embabel.agent.api.common.PromptRunner
 */
@ApiStatus.Internal
interface LlmOperations {

    /**
     * Generate text in the context of an AgentProcess.
     * @param prompt Prompt to generate text from
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     */
    fun generate(
        prompt: String,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?,
    ): String = createObject(
        messages = listOf(UserMessage(prompt)),
        interaction = interaction,
        outputClass = String::class.java,
        agentProcess = agentProcess,
        action = action,
    )

    /**
     * Create an output object, in the context of an AgentProcess.
     * @param messages messages in the conversation so far. Could just be user message.
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param outputClass Class of the output object
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @throws com.embabel.agent.core.support.InvalidLlmReturnFormatException if the LLM returns an invalid object
     * @throws com.embabel.agent.core.support.InvalidLlmReturnTypeException if the LLM returns an object that fails validation
     */
    @Throws(
        InvalidLlmReturnFormatException::class,
        InvalidLlmReturnTypeException::class
    )
    fun <O> createObject(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O

    /**
     * Try to create an output object in the context of an AgentProcess.
     * Return a failure result if the LLM does not have enough information to create the object.
     * @param messages messages
     * @param interaction Llm options and tool callbacks to use, plus unique identifier
     * @param outputClass Class of the output object
     * @param agentProcess Agent process we are running within
     * @param action Action we are running within if we are running within an action
     * @throws InvalidLlmReturnFormatException if the LLM returns an object of the wrong type
     */
    fun <O> createObjectIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<O>

    /**
     * Low level transform, not necessarily aware of platform
     * This is a convenience overload that creates a UserMessage
     * from a String prompt
     * @param prompt user prompt. Will become the last user message
     * @param interaction The LLM call options
     * @param outputClass Class of the output object
     * @param llmRequestEvent Event already published for this request if one has been
     */
    fun <O> doTransform(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O =
        doTransform(
            messages = listOf(UserMessage(prompt)),
            interaction = interaction,
            outputClass = outputClass,
            llmRequestEvent = llmRequestEvent,
        )

    /**
     * Low level transform, not necessarily aware of platform
     * @param messages messages
     * @param interaction The LLM call options
     * @param outputClass Class of the output object
     * @param llmRequestEvent Event already published for this request if one has been
     */
    fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O

}