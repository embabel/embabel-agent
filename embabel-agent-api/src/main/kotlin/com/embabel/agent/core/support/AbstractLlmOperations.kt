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
package com.embabel.agent.core.support

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.event.LlmRequestEvent
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.ToolDecorator
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.*
import com.embabel.common.util.time
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import java.time.Duration

/**
 * Convenient superclass for LlmOperations implementations,
 * which should normally extend this
 * Find all tool callbacks and decorate them to be aware of the platform
 * Also emits events.
 */
abstract class AbstractLlmOperations(
    private val toolDecorator: ToolDecorator,
    private val modelProvider: ModelProvider,
    private val autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
) : LlmOperations {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    override fun generate(
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

    final override fun <O> createObject(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O {
        val (allToolCallbacks, llmRequestEvent) = setup(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            messages = messages,
            outputClass = outputClass,
        )
        val (response, ms) = time {
            doTransform(
                messages = messages,
                interaction = interaction.copy(toolCallbacks = allToolCallbacks.map {
                    toolDecorator.decorate(
                        tool = it,
                        agentProcess = agentProcess,
                        action = action,
                        llmOptions = interaction.llm,
                    )
                }),
                outputClass = outputClass,
                llmRequestEvent = llmRequestEvent,
            )
        }
        logger.debug("LLM response={}", response)
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent.responseEvent(
                response = response,
                runningTime = Duration.ofMillis(ms),
            ),
        )
        return response
    }

    final override fun <O> createObjectIfPossible(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<O> {
        val (allToolCallbacks, llmRequestEvent) = setup(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            messages = listOf(UserMessage(prompt)),
            outputClass = outputClass,
        )
        val (response, ms) = time {
            doTransformIfPossible(
                prompt = prompt,
                interaction = interaction.copy(toolCallbacks = allToolCallbacks.map {
                    toolDecorator.decorate(
                        tool = it,
                        agentProcess = agentProcess,
                        action = action,
                        llmOptions = interaction.llm,
                    )
                }),
                outputClass = outputClass,
                llmRequestEvent = llmRequestEvent,
            )
        }
        logger.debug("LLM response={}", response)
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent.maybeResponseEvent(
                response = response,
                runningTime = Duration.ofMillis(ms),
            ),
        )
        return response
    }

    protected fun chooseLlm(
        llmOptions: LlmOptions,
    ): Llm {
        val crit: ModelSelectionCriteria = when (llmOptions.criteria) {
            null -> DefaultModelSelectionCriteria
            is AutoModelSelectionCriteria ->
                autoLlmSelectionCriteriaResolver.resolveAutoLlm()

            else -> llmOptions.criteria ?: DefaultModelSelectionCriteria
        }
        return modelProvider.getLlm(crit)
    }

    protected abstract fun <O> doTransformIfPossible(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O>

    private fun <O> setup(
        agentProcess: AgentProcess,
        interaction: LlmInteraction,
        action: Action?,
        messages: List<Message>,
        outputClass: Class<O>,
    ): Pair<Collection<ToolCallback>, LlmRequestEvent<O>> {
        val toolGroupResolver = agentProcess.processContext.platformServices.agentPlatform.toolGroupResolver
        val allToolCallbacks =
            interaction.resolveToolCallbacks(
                toolGroupResolver,
            )
        val llmRequestEvent = LlmRequestEvent(
            agentProcess = agentProcess,
            action = action,
            outputClass = outputClass,
            interaction = interaction.copy(
                toolCallbacks = allToolCallbacks,
            ),
            llm = chooseLlm(llmOptions = interaction.llm),
            messages = messages,
        )
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent
        )
        logger.debug(
            "Expanded toolCallbacks from {}: {}",
            llmRequestEvent.interaction.toolCallbacks.map { it.toolDefinition.name() },
            allToolCallbacks.map { it.toolDefinition.name() })
        return Pair(allToolCallbacks, llmRequestEvent)
    }
}
