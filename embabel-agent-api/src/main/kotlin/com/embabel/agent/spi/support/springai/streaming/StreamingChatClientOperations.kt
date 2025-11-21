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
package com.embabel.agent.spi.support.springai.streaming

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.event.LlmRequestEvent
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.streaming.StreamingLlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.PROMPT_ELEMENT_SEPARATOR
import com.embabel.agent.spi.support.springai.toSpringAiMessage
import com.embabel.chat.Message
import com.embabel.common.ai.converters.streaming.StreamingJacksonOutputConverter
import com.embabel.common.core.streaming.StreamingEvent
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

/**
 * Streaming implementation that delegates to ChatClientLlmOperations for core LLM functionality
 * while adding Spring AI streaming capabilities using ChatClient.stream().
 *
 * This class provides real-time streaming of:
 * - Text chunks as they arrive from the LLM
 * - Typed objects parsed from JSONL responses
 * - Mixed content with both objects and LLM thinking
 */
internal class StreamingChatClientOperations(
    private val chatClientLlmOperations: ChatClientLlmOperations,
) : StreamingLlmOperations {

    /**
     * Build prompt contributions string from interaction and LLM contributors.
     */
    private fun buildPromptContributions(interaction: LlmInteraction, llm: com.embabel.common.ai.model.Llm): String {
        return (interaction.promptContributors + llm.promptContributors)
            .joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }
    }

    /**
     * Build Spring AI Prompt from messages and contributions.
     */
    private fun buildSpringAiPrompt(messages: List<Message>, promptContributions: String): Prompt {
        return Prompt(
            buildList {
                if (promptContributions.isNotEmpty()) {
                    add(SystemMessage(promptContributions))
                }
                addAll(messages.map { it.toSpringAiMessage() })
            }
        )
    }

    override fun generateStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?
    ): Flux<String> {
        return doTransformStream(messages, interaction, null)
    }

    override fun <O> createObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?
    ): Flux<O> {
        return doTransformObjectStream(messages, interaction, outputClass, null)
    }

    override fun <O> createObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?
    ): Flux<StreamingEvent<O>> {
        return doTransformObjectStreamWithThinking(messages, interaction, outputClass, null)
    }

    override fun <O> createObjectStreamIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?
    ): Flux<Result<O>> {
        return createObjectStream(messages, interaction, outputClass, agentProcess, action)
            .map { Result.success(it) }
            .onErrorResume { throwable ->
                Flux.just(Result.failure(throwable))
            }
    }

    override fun doTransformStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        llmRequestEvent: LlmRequestEvent<String>?
    ): Flux<String> {
        // Use ChatClientLlmOperations to get LLM and create ChatClient
        val llm = chatClientLlmOperations.getLlm(interaction)
        val chatClient = chatClientLlmOperations.createChatClient(llm)

        // Build prompt using helper methods
        val promptContributions = buildPromptContributions(interaction, llm)
        val springAiPrompt = buildSpringAiPrompt(messages, promptContributions)

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

        return chatClient
            .prompt(springAiPrompt)
            .toolCallbacks(interaction.toolCallbacks)
            .options(chatOptions)
            .stream()
            .content()
    }

    override fun <O> doTransformObjectStream(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?
    ): Flux<O> {
        // Delegate to ChatClientLlmOperations for LLM setup, then use Spring AI streaming
        val llm = chatClientLlmOperations.getLlm(interaction)
        val chatClient = chatClientLlmOperations.createChatClient(llm)

        // Build prompt using helper methods
        val promptContributions = buildPromptContributions(interaction, llm)
        val springAiPrompt = buildSpringAiPrompt(messages, promptContributions)

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

        val streamingConverter = StreamingJacksonOutputConverter(
            clazz = outputClass,
            objectMapper = chatClientLlmOperations.objectMapper,
            propertyFilter = interaction.propertyFilter
        )

        return chatClient
            .prompt(springAiPrompt)
            .toolCallbacks(interaction.toolCallbacks)
            .options(chatOptions)
            .stream()
            .content()
            .reduce("") { accumulator, chunk -> accumulator + chunk }
            .flatMapMany { completeText ->
                streamingConverter.convertStream(completeText)
            }
    }

    override fun <O> doTransformObjectStreamWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?
    ): Flux<StreamingEvent<O>> {
        // Delegate to ChatClientLlmOperations for LLM setup, then use Spring AI streaming
        val llm = chatClientLlmOperations.getLlm(interaction)
        val chatClient = chatClientLlmOperations.createChatClient(llm)

        // Build prompt using helper methods
        val promptContributions = buildPromptContributions(interaction, llm)
        val springAiPrompt = buildSpringAiPrompt(messages, promptContributions)

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

        val streamingConverter = StreamingJacksonOutputConverter(
            clazz = outputClass,
            objectMapper = chatClientLlmOperations.objectMapper,
            propertyFilter = interaction.propertyFilter
        )

        return chatClient
            .prompt(springAiPrompt)
            .toolCallbacks(interaction.toolCallbacks)
            .options(chatOptions)
            .stream()
            .content()
            .reduce("") { accumulator, chunk -> accumulator + chunk }
            .flatMapMany { completeText ->
                streamingConverter.convertStreamWithThinking(completeText)
            }
    }
}
