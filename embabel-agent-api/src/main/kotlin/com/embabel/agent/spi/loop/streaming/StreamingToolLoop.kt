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
package com.embabel.agent.spi.loop.streaming

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.spi.loop.AutoCorrectionPolicy
import com.embabel.agent.spi.loop.MaxIterationsExceededException
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.ToolNotFoundAction
import com.embabel.agent.spi.loop.ToolNotFoundPolicy
import com.embabel.agent.spi.loop.support.applyToolInjection
import com.embabel.agent.spi.loop.support.executeTool
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper

/**
 * Provider-neutral counterpart of [com.embabel.agent.spi.loop.ToolLoop].
 *
 * Implementations manage tool execution and conversation continuation while delegating
 * each individual streaming inference to an [LlmMessageStreamer]. The returned [Flux]
 * contains content from every inference turn in arrival order; provider-level completion
 * events remain internal to the loop.
 */
fun interface StreamingToolLoop {
    fun execute(initialMessages: List<Message>, initialTools: List<Tool>): Flux<String>

    companion object {
        /**
         * Create the default provider-neutral streaming tool loop while keeping its
         * implementation internal to Embabel's SPI surface.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            messageStreamer: LlmMessageStreamer,
            objectMapper: ObjectMapper,
            injectionStrategy: ToolInjectionStrategy = ToolInjectionStrategy.NONE,
            maxIterations: Int = 20,
            toolDecorator: ((Tool) -> Tool)? = null,
            toolCallInspectors: List<ToolCallInspector> = emptyList(),
            toolCallContext: ToolCallContext = ToolCallContext.EMPTY,
            toolNotFoundPolicy: ToolNotFoundPolicy = AutoCorrectionPolicy(),
        ): StreamingToolLoop = DefaultStreamingToolLoop(
            messageStreamer = messageStreamer,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = maxIterations,
            toolDecorator = toolDecorator,
            toolCallInspectors = toolCallInspectors,
            toolCallContext = toolCallContext,
            toolNotFoundPolicy = toolNotFoundPolicy,
        )
    }
}

/**
 * Default Embabel-owned streaming tool loop.
 *
 * Code flow for each subscription:
 * 1. Create isolated mutable conversation and tool state.
 * 2. Ask [LlmMessageStreamer] to perform one inference.
 * 3. Forward [LlmInferenceStreamEvent.Content] immediately.
 * 4. Use [LlmInferenceStreamEvent.Complete] to append the assembled assistant message.
 * 5. If the assistant requested tools, execute them, append results, apply
 *    [ToolInjectionStrategy], and recursively start the next inference.
 * 6. Complete when the assistant requests no more tools, or emit direct tool content
 *    when a tool has `returnDirect=true`.
 *
 * `Flux.defer` is deliberately used at the entry point and for every turn. It creates
 * state at subscription time and prevents conversation history, iteration counters, or
 * dynamically injected tools from leaking between multiple subscriptions to the same Flux.
 * `Flux.concatMap` preserves the ordering between content chunks, terminal processing,
 * tool execution, and the subsequent inference.
 *
 * Multiple tool calls in one assistant message are executed sequentially, matching
 * [com.embabel.agent.spi.loop.support.DefaultToolLoop]. This preserves declared order
 * and deterministic `returnDirect`, inspector, and dynamic-injection semantics. Parallel
 * execution would require a separate contract for those ordering-sensitive behaviours.
 *
 * @param maxIterations Upper bound for inference turns. Production wiring supplies
 * [com.embabel.agent.core.support.LlmInteraction.maxToolIterations]; `20` is only the
 * direct-construction default.
 */
internal class DefaultStreamingToolLoop(
    private val messageStreamer: LlmMessageStreamer,
    private val objectMapper: ObjectMapper,
    private val injectionStrategy: ToolInjectionStrategy = ToolInjectionStrategy.NONE,
    private val maxIterations: Int = 20,
    private val toolDecorator: ((Tool) -> Tool)? = null,
    private val toolCallInspectors: List<ToolCallInspector> = emptyList(),
    private val toolCallContext: ToolCallContext = ToolCallContext.EMPTY,
    private val toolNotFoundPolicy: ToolNotFoundPolicy = AutoCorrectionPolicy(),
) : StreamingToolLoop {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun execute(
        initialMessages: List<Message>,
        initialTools: List<Tool>,
    ): Flux<String> = Flux.defer {
        streamTurn(
            State(
                conversationHistory = initialMessages.toMutableList(),
                availableTools = initialTools.toMutableList(),
            )
        )
    }

    private fun streamTurn(state: State): Flux<String> = Flux.defer {
        if (state.iterations >= maxIterations) {
            return@defer Flux.error(MaxIterationsExceededException(maxIterations))
        }
        state.iterations++
        logger.debug(
            "Streaming tool loop iteration {} with {} available tools",
            state.iterations,
            state.availableTools.size,
        )

        messageStreamer.streamInference(
            messages = state.conversationHistory.toList(),
            tools = state.availableTools.toList(),
        ).concatMap { event ->
            when (event) {
                is LlmInferenceStreamEvent.Content -> Flux.just(event.text)
                is LlmInferenceStreamEvent.Complete -> continueFrom(event, state)
            }
        }
    }

    /**
     * Processes the assembled terminal message for one inference turn.
     *
     * Content was already forwarded as [LlmInferenceStreamEvent.Content], so an assistant
     * response without tool calls contributes no additional item and completes with
     * [Flux.empty]. Tool calls append their results to the conversation before the next
     * inference starts. A direct-return tool instead emits its result and ends the loop.
     */
    private fun continueFrom(
        event: LlmInferenceStreamEvent.Complete,
        state: State,
    ): Flux<String> {
        state.conversationHistory.add(event.message)
        val assistant = event.message as? AssistantMessageWithToolCalls
            ?: return Flux.empty()
        if (assistant.toolCalls.isEmpty()) return Flux.empty()

        for (toolCall in assistant.toolCalls) {
            val directResult = executeToolCall(toolCall, state)
            if (directResult != null) return Flux.just(directResult)
        }
        return streamTurn(state)
    }

    /**
     * Executes one tool call and returns direct content when the tool short-circuits the loop.
     */
    private fun executeToolCall(toolCall: ToolCall, state: State): String? {
        val tool = state.availableTools.find { it.definition.name == toolCall.name }
        if (tool == null) {
            when (val action = toolNotFoundPolicy.handle(toolCall.name, state.availableTools)) {
                is ToolNotFoundAction.Throw -> throw action.exception
                is ToolNotFoundAction.FeedbackToModel -> {
                    logger.warn(action.message)
                    state.conversationHistory.add(
                        ToolResultMessage(toolCall.id, toolCall.name, action.message)
                    )
                    return null
                }
            }
        }

        toolNotFoundPolicy.onToolFound()
        logger.debug("Executing streaming tool: {}", toolCall.name)
        val executed = executeTool(
            tool = tool,
            toolCall = toolCall,
            toolCallContext = toolCallContext,
            toolCallInspectors = toolCallInspectors,
        )

        applyInjection(toolCall, executed.content, state)
        state.conversationHistory.add(ToolResultMessage(toolCall.id, toolCall.name, executed.content))
        if (tool.metadata.returnDirect) {
            logger.info("Tool '{}' has returnDirect=true — completing streaming loop", toolCall.name)
            return executed.content
        }
        return null
    }

    private fun applyInjection(toolCall: ToolCall, resultContent: String, state: State) {
        applyToolInjection(
            toolCall = toolCall,
            resultContent = resultContent,
            conversationHistory = state.conversationHistory,
            availableTools = state.availableTools,
            iteration = state.iterations,
            injectionStrategy = injectionStrategy,
            objectMapper = objectMapper,
            toolDecorator = toolDecorator,
            logger = logger,
        )
    }

    private data class State(
        val conversationHistory: MutableList<Message>,
        val availableTools: MutableList<Tool>,
        var iterations: Int = 0,
    )
}
