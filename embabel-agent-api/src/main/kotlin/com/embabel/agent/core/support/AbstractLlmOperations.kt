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
package com.embabel.agent.core.support

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.*
import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.spi.validation.ValidationPromptGenerator
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.*
import com.embabel.common.util.time
import jakarta.validation.Validator
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
    private val validator: Validator,
    private val validationPromptGenerator: ValidationPromptGenerator = DefaultValidationPromptGenerator(),
    private val autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver,
    protected val dataBindingProperties: LlmDataBindingProperties,
) : LlmOperations {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    final override fun <O> createObject(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O {
        val (allToolCallbacks, llmRequestEvent) = getToolsAndEvent(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            messages = messages,
            outputClass = outputClass,
        )

        val interactionWithToolDecoration = interaction.copy(
            toolCallbacks = allToolCallbacks.map {
                toolDecorator.decorate(
                    tool = it,
                    agentProcess = agentProcess,
                    action = action,
                    llmOptions = interaction.llm,
                )
            })

        val (createdObject, ms) = time {
            val initialMessages =
                if (interaction.validation &&
                    validator.getConstraintsForClass(outputClass).isBeanConstrained &&
                    dataBindingProperties.sendValidationInfo
                ) {
                    messages + UserMessage(
                        validationPromptGenerator.generateRequirementsPrompt(
                            validator = validator,
                            outputClass = outputClass,
                        )
                    )
                } else {
                    messages
                }

            var candidate = doTransform(
                messages = initialMessages,
                interaction = interactionWithToolDecoration,
                outputClass = outputClass,
                llmRequestEvent = llmRequestEvent,
            )
            if (interaction.validation) {
                var constraintViolations = validator.validate(candidate)
                if (constraintViolations.isNotEmpty()) {
                    // If we had violations, try again, once, before throwing an exception
                    candidate = doTransform(
                        messages = messages + UserMessage(
                            validationPromptGenerator.generateViolationsReport(
                                constraintViolations
                            )
                        ),
                        interaction = interactionWithToolDecoration,
                        outputClass = outputClass,
                        llmRequestEvent = llmRequestEvent,
                    )
                    constraintViolations = validator.validate(candidate)
                    if (constraintViolations.isNotEmpty()) {
                        throw InvalidLlmReturnTypeException(
                            returnedObject = candidate as Any,
                            constraintViolations = constraintViolations,
                        )
                    }
                }
            }
            candidate
        }
        logger.debug("LLM response={}", createdObject)
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent.responseEvent(
                response = createdObject,
                runningTime = Duration.ofMillis(ms),
            ),
        )
        return createdObject
    }

    final override fun <O> createObjectIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<O> {
        val (allToolCallbacks, llmRequestEvent) = getToolsAndEvent(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            messages = messages,
            outputClass = outputClass,
        )
        val (response, ms) = time {
            doTransformIfPossible(
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
            is AutoModelSelectionCriteria ->
                autoLlmSelectionCriteriaResolver.resolveAutoLlm()

            else -> llmOptions.criteria
        }
        return modelProvider.getLlm(crit)
    }

    protected abstract fun <O> doTransformIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O>

    private fun <O> getToolsAndEvent(
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
        agentProcess.processContext.onProcessEvent(llmRequestEvent)
        logger.debug(
            "Expanded toolCallbacks from {}: {}",
            llmRequestEvent.interaction.toolCallbacks.map { it.toolDefinition.name() },
            allToolCallbacks.map { it.toolDefinition.name() })
        return Pair(allToolCallbacks, llmRequestEvent)
    }
}
