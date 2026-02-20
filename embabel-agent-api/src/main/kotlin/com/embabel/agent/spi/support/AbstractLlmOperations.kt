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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.spi.validation.ValidationPromptGenerator
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.AutoModelSelectionCriteria
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.util.time
import jakarta.validation.Validator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Log message constants to avoid duplication
private const val LLM_TIMEOUT_MESSAGE = "LLM {}: attempt {} timed out after {}ms"
private const val LLM_INTERRUPTED_MESSAGE = "LLM {}: attempt {} was interrupted"

/**
 * Convenient superclass for LlmOperations implementations,
 * which should normally extend this
 * Find all tool callbacks and decorate them to be aware of the platform
 * Also emits events.
 */
abstract class AbstractLlmOperations(
    protected val toolDecorator: ToolDecorator,
    private val modelProvider: ModelProvider,
    private val validator: Validator,
    private val validationPromptGenerator: ValidationPromptGenerator = DefaultValidationPromptGenerator(),
    private val autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver,
    protected val dataBindingProperties: LlmDataBindingProperties,
    protected val promptsProperties: LlmOperationsPromptsProperties = LlmOperationsPromptsProperties(),
    protected val asyncer: Asyncer,
) : LlmOperations {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    /**
     * Get timeout in milliseconds from options or default.
     */
    protected fun getTimeoutMillis(llmOptions: LlmOptions): Long =
        (llmOptions.timeout ?: promptsProperties.defaultTimeout).toMillis()

    /**
     * Execute an LLM operation with timeout.
     * Wraps the operation in a CompletableFuture with configured timeout.
     *
     * @param interactionId Identifier for logging
     * @param llmOptions Options containing timeout configuration
     * @param attempt Current retry attempt number for logging
     * @param operation The LLM operation to execute
     * @return The result of the operation
     * @throws RuntimeException if the operation times out or fails
     */
    protected fun <T> executeWithTimeout(
        interactionId: String,
        llmOptions: LlmOptions,
        attempt: Int = 1,
        operation: () -> T,
    ): T {
        val timeoutMillis = getTimeoutMillis(llmOptions)

        val future = asyncer.async(operation)

        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            logger.warn(LLM_TIMEOUT_MESSAGE, interactionId, attempt, timeoutMillis)
            throw RuntimeException(
                "LLM call for interaction $interactionId timed out after ${timeoutMillis}ms",
                e
            )
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            logger.warn(LLM_INTERRUPTED_MESSAGE, interactionId, attempt)
            throw RuntimeException(
                "LLM call for interaction $interactionId was interrupted",
                e
            )
        } catch (e: ExecutionException) {
            future.cancel(true)
            val cause = e.cause
            when (cause) {
                is RuntimeException -> throw cause
                is Exception -> throw RuntimeException(
                    "LLM call for interaction $interactionId failed",
                    cause
                )
                else -> throw RuntimeException(
                    "LLM call for interaction $interactionId failed with unknown error",
                    e
                )
            }
        }
    }

    final override fun <O> createObject(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O {
        val (allTools, llmRequestEvent) = getToolsAndEvent(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            messages = messages,
            outputClass = outputClass,
        )

        val interactionWithToolDecoration = interaction.copy(
            tools = allTools.map {
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

            // Wrap doTransform with retry for transient failures (e.g., malformed JSON)
            // and timeout for operations that take too long
            var candidate = dataBindingProperties.retryTemplate(interaction.id.value)
                .execute<O, Exception> {
                    executeWithTimeout(
                        interactionId = interaction.id.value,
                        llmOptions = interaction.llm,
                    ) {
                        doTransform(
                            messages = initialMessages,
                            interaction = interactionWithToolDecoration,
                            outputClass = outputClass,
                            llmRequestEvent = llmRequestEvent,
                        )
                    }
                }
            if (interaction.validation) {
                var constraintViolations = validator.validate(candidate)
                if (constraintViolations.isNotEmpty()) {
                    // If we had violations, try again, once, before throwing an exception
                    candidate = dataBindingProperties.retryTemplate(interaction.id.value)
                        .execute<O, Exception> {
                            executeWithTimeout(
                                interactionId = interaction.id.value,
                                llmOptions = interaction.llm,
                            ) {
                                doTransform(
                                    messages = messages + UserMessage(
                                        validationPromptGenerator.generateViolationsReport(
                                            constraintViolations
                                        )
                                    ),
                                    interaction = interactionWithToolDecoration,
                                    outputClass = outputClass,
                                    llmRequestEvent = llmRequestEvent,
                                )
                            }
                        }
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
        val (allTools, llmRequestEvent) = getToolsAndEvent(
            agentProcess = agentProcess,
            interaction = interaction,
            action = action,
            messages = messages,
            outputClass = outputClass,
        )
        val (response, ms) = time {
            doTransformIfPossible(
                messages = messages,
                interaction = interaction.copy(tools = allTools.map {
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
    ): LlmService<*> {
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
    ): Pair<List<Tool>, LlmRequestEvent<O>> {
        val toolGroupResolver = agentProcess.processContext.platformServices.agentPlatform.toolGroupResolver
        val allTools = interaction.resolveTools(toolGroupResolver)
        val llmRequestEvent = LlmRequestEvent(
            agentProcess = agentProcess,
            action = action,
            outputClass = outputClass,
            interaction = interaction.copy(tools = allTools),
            llmMetadata = chooseLlm(llmOptions = interaction.llm),
            messages = messages,
        )
        agentProcess.processContext.onProcessEvent(llmRequestEvent)
        logger.debug(
            "Expanded tools from {}: {}",
            llmRequestEvent.interaction.tools.map { it.definition.name },
            allTools.map { it.definition.name })
        return Pair(allTools, llmRequestEvent)
    }
}
