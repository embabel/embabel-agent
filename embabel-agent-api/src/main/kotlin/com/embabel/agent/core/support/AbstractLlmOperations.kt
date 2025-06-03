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
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.ToolDecorator
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
) : LlmOperations {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    override fun generate(
        prompt: String,
        interaction: LlmInteraction,
        agentProcess: AgentProcess,
        action: Action?
    ): String = createObject(
        prompt = prompt,
        interaction = interaction,
        outputClass = String::class.java,
        agentProcess = agentProcess,
        action = action,
    )

    final override fun <O> createObject(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O {
        val (allToolCallbacks, literalPrompt, llmRequestEvent) = setup<O>(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            prompt = prompt,
            outputClass = outputClass,
        )
        val (response, ms) = time {
            doTransform(
                prompt = literalPrompt,
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
        action: Action?
    ): Result<O> {
        val (allToolCallbacks, prompt, llmRequestEvent) = setup<O>(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            prompt = prompt,
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
        prompt: String,
        outputClass: Class<O>,
    ): Triple<Collection<ToolCallback>, String, LlmRequestEvent<O>> {
        val toolGroupResolver = agentProcess.processContext.platformServices.agentPlatform.toolGroupResolver
        val allToolCallbacks =
            interaction.resolveToolCallbacks(
                toolGroupResolver,
            )
        val llmRequestEvent = LlmRequestEvent(
            agentProcess = agentProcess,
            outputClass = outputClass,
            interaction = interaction.copy(
                toolCallbacks = allToolCallbacks,
            ),
            prompt = prompt,
        )
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent
        )
        logger.debug(
            "Expanded toolCallbacks from {}: {}",
            llmRequestEvent.interaction.toolCallbacks.map { it.toolDefinition.name() },
            allToolCallbacks.map { it.toolDefinition.name() })
        return Triple(allToolCallbacks, prompt, llmRequestEvent)
    }
}
