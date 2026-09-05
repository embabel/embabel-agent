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
import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperationsFactory
import com.embabel.agent.spi.support.streaming.StreamingLlmOperationsImpl
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
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.PreResolvedModelSelectionCriteria
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.util.time
import tools.jackson.databind.ObjectMapper
import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import java.lang.reflect.Field
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Predicate
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
    internal open val objectMapper: ObjectMapper,
) : LlmOperations, StreamingLlmOperationsFactory {

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
            logger.warn(LLM_TIMEOUT_MESSAGE, interactionId, attempt, "%,d".format(Locale.ROOT, timeoutMillis))
            throw RuntimeException(
                "LLM call for interaction $interactionId timed out after ${"%,d".format(Locale.ROOT, timeoutMillis)}ms",
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
            when (val cause = e.cause) {
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

    /**
     * The attempts made in one logical LLM call. Each runs under the data binding retry policy,
     * which forgives transient failures such as malformed JSON, and the configured timeout.
     * Publishes an [com.embabel.agent.api.event.LlmRetryEvent] as each retry begins.
     *
     * A retry is reported when it actually happens rather than when the attempt before it fails,
     * so no event has to guess whether the policy will allow another attempt.
     */
    private inner class Attempts(
        private val llmRequestEvent: LlmRequestEvent<*>,
        private val interaction: LlmInteraction,
    ) {
        /** Round trips made to the model in this call, counted across every [attempt] block it runs. */
        var made: Int = 0
            private set

        /**
         * Round trips this call is allowed, counted the same way. Every [attempt] block runs its own
         * retry sequence with its own budget, so the budget grows as blocks start. Counting both the
         * same way keeps [made] and [maxAttempts] describing the same thing: the whole call.
         */
        var maxAttempts: Int = 0
            private set

        /** How long the last failed attempt took, excluding the backoff wait that follows it. */
        private var lastFailure: Duration = Duration.ZERO

        fun <O> attempt(operation: () -> O): O {
            maxAttempts += dataBindingProperties.maxAttempts
            return dataBindingProperties.retryTemplate(interaction.id.value)
                .execute<O, Exception> { context ->
                    // Null until an attempt has failed, so this fires only on a real retry
                    context.lastThrowable?.let { lastThrowable ->
                        llmRequestEvent.agentProcess.processContext.onProcessEvent(
                            llmRequestEvent.retryEvent(
                                throwable = lastThrowable,
                                attempts = made,
                                maxAttempts = maxAttempts,
                                runningTime = lastFailure,
                            )
                        )
                    }
                    made++
                    val attemptStarted = Instant.now()
                    try {
                        executeWithTimeout(
                            interactionId = interaction.id.value,
                            llmOptions = interaction.llm,
                            attempt = made,
                            operation = operation,
                        )
                    } catch (t: Throwable) {
                        // Timed here, not at the next callback, which only runs after the backoff wait
                        lastFailure = Duration.between(attemptStarted, Instant.now())
                        throw t
                    }
                }
        }
    }

    /**
     * Run one logical LLM call, publishing an [com.embabel.agent.api.event.LlmCallFailedEvent] if it
     * ends without a response. [call] makes each round trip through [Attempts.attempt], so a failure
     * anywhere in the call - an exhausted retry, or a response that never passes validation - is
     * reported once, counting every round trip the call made.
     */
    private fun <O> withFailureEvents(
        llmRequestEvent: LlmRequestEvent<*>,
        interaction: LlmInteraction,
        call: Attempts.() -> O,
    ): O {
        val attempts = Attempts(llmRequestEvent, interaction)
        return try {
            attempts.call()
        } catch (e: Exception) {
            // Exception, not Throwable: an Error means the JVM is in trouble (out of memory, stack
            // exhausted). Calling listeners then can turn a bad situation into a worse one, and the
            // listener would have nothing useful to do with it anyway. Errors propagate untouched.
            // Control flow signals such as ReplanRequestedException end the call without failing it
            if (e !is ToolControlFlowSignal) {
                llmRequestEvent.agentProcess.processContext.onProcessEvent(
                    llmRequestEvent.failureEvent(
                        throwable = e,
                        attempts = attempts.made,
                        maxAttempts = attempts.maxAttempts,
                        runningTime = Duration.between(llmRequestEvent.timestamp, Instant.now()),
                    )
                )
            }
            throw e
        }
    }

    /** A call that is a single round trip to the model. */
    private fun <O> withRetry(
        llmRequestEvent: LlmRequestEvent<*>,
        interaction: LlmInteraction,
        operation: () -> O,
    ): O = withFailureEvents(llmRequestEvent, interaction) { attempt(operation) }

    final override fun <O> createObject(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): O {
        // Shadowed deliberately. After this line the resolved interaction IS the interaction for
        // the rest of the method, and shadowing makes the unresolved one unreachable. A distinct
        // name would leave both in scope, differing only in whether a role has become a concrete
        // model plus its hyperparameters - and picking the wrong one is not a compile error, it is
        // a call that silently skips role resolution and runs on the default model.
        @Suppress("NAME_SHADOWING")
        val interaction = withRoleResolved(interaction)

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
                            fieldFilter = interaction.fieldFilter,
                        )
                    )
                } else {
                    messages
                }

            // Binding and validation are one LLM call: a response that never validates is a
            // failure of that call, reported like an exhausted retry.
            withFailureEvents(llmRequestEvent, interaction) {
                var candidate = attempt {
                    doTransform(
                        messages = initialMessages,
                        interaction = interactionWithToolDecoration,
                        outputClass = outputClass,
                        llmRequestEvent = llmRequestEvent,
                    )
                }
                if (interaction.validation) {
                    var constraintViolations = validator.validate(candidate)
                    constraintViolations =
                        filterConstraintViolations(constraintViolations, outputClass, interaction.fieldFilter)
                    if (constraintViolations.isNotEmpty()) {
                        // If we had violations, try again, once, before throwing an exception
                        candidate = attempt {
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
                        constraintViolations = validator.validate(candidate)
                        constraintViolations =
                            filterConstraintViolations(constraintViolations, outputClass, interaction.fieldFilter)
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
        }
        logger.debug("LLM createdObject response={}", createdObject)
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent.responseEvent(
                response = createdObject,
                runningTime = Duration.ofMillis(ms),
            ),
        )
        return createdObject
    }

    private fun <O> filterConstraintViolations(
        constraintViolations: Set<ConstraintViolation<O>>,
        outputClass: Class<O>,
        fieldFilter: Predicate<Field>,
    ): Set<ConstraintViolation<O>> =
        constraintViolations.filterTo(mutableSetOf()) { violation ->
            runCatching { outputClass.getDeclaredField(violation.propertyPath.toString()) }
                .map { fieldFilter.test(it) }
                .getOrDefault(true)
        }

    final override fun <O> createObjectIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<O> {
        // Shadowed deliberately. After this line the resolved interaction IS the interaction for
        // the rest of the method, and shadowing makes the unresolved one unreachable. A distinct
        // name would leave both in scope, differing only in whether a role has become a concrete
        // model plus its hyperparameters - and picking the wrong one is not a compile error, it is
        // a call that silently skips role resolution and runs on the default model.
        @Suppress("NAME_SHADOWING")
        val interaction = withRoleResolved(interaction)

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
            }
        )

        val (response, ms) = time {
            withRetry(llmRequestEvent, interaction) {
                doTransformIfPossible(
                    messages = messages,
                    interaction = interactionWithToolDecoration,
                    outputClass = outputClass,
                    llmRequestEvent = llmRequestEvent,
                )
            }
        }
        logger.debug("LLM createObjectIfPossible response={}", response)
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent.maybeResponseEvent(
                response = response,
                runningTime = Duration.ofMillis(ms),
            ),
        )
        return response
    }

    final override fun <O> createObjectWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): ThinkingResponse<O> {
        // Shadowed deliberately. After this line the resolved interaction IS the interaction for
        // the rest of the method, and shadowing makes the unresolved one unreachable. A distinct
        // name would leave both in scope, differing only in whether a role has become a concrete
        // model plus its hyperparameters - and picking the wrong one is not a compile error, it is
        // a call that silently skips role resolution and runs on the default model.
        @Suppress("NAME_SHADOWING")
        val interaction = withRoleResolved(interaction)

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
            }
        )

        val (thinkingResponse, ms) = time {
            withRetry(llmRequestEvent, interaction) {
                doTransformWithThinking(
                    messages = messages,
                    interaction = interactionWithToolDecoration,
                    outputClass = outputClass,
                    llmRequestEvent = llmRequestEvent,
                )
            }
        }
        logger.debug("LLM thinking response={}", thinkingResponse)
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent.thinkingResponseEvent(
                response = thinkingResponse,
                runningTime = Duration.ofMillis(ms),
            ),
        )
        return thinkingResponse
    }

    final override fun <O> createObjectIfPossibleWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        agentProcess: AgentProcess,
        action: Action?,
    ): Result<ThinkingResponse<O>> {
        // Shadowed deliberately. After this line the resolved interaction IS the interaction for
        // the rest of the method, and shadowing makes the unresolved one unreachable. A distinct
        // name would leave both in scope, differing only in whether a role has become a concrete
        // model plus its hyperparameters - and picking the wrong one is not a compile error, it is
        // a call that silently skips role resolution and runs on the default model.
        @Suppress("NAME_SHADOWING")
        val interaction = withRoleResolved(interaction)

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
            }
        )

        val (response, ms) = time {
            withRetry(llmRequestEvent, interaction) {
                doTransformWithThinkingIfPossible(
                    messages = messages,
                    interaction = interactionWithToolDecoration,
                    outputClass = outputClass,
                    llmRequestEvent = llmRequestEvent,
                )
            }
        }
        logger.debug("LLM createObjectIfPossibleWithThinking response={}", response)
        agentProcess.processContext.onProcessEvent(
            llmRequestEvent.maybeThinkingResponseEvent(
                response = response,
                runningTime = Duration.ofMillis(ms),
            ),
        )
        return response
    }

    /**
     * Resolve any role named by this interaction before anything reads its options: a role can
     * carry hyperparameters, and which model it means depends on the provider active for this call.
     *
     * Interactions naming no role are returned untouched, so the common path costs nothing.
     *
     * Idempotent, so a subclass may call it on a path this class has already resolved: resolution
     * replaces the role criteria with a pre-resolved one, and a second call sees no role and does
     * nothing. That is what lets the low-level `doTransform` entry points resolve for themselves
     * without double-resolving the `createObject` path that reaches them.
     */
    protected fun withRoleResolved(interaction: LlmInteraction): LlmInteraction {
        val resolved = withRoleResolved(interaction.llm)
        return if (resolved === interaction.llm) interaction else interaction.copy(llm = resolved)
    }

    /**
     * As above, for the paths that carry options rather than a whole interaction - streaming,
     * and the capability queries that pick a model without running a prompt.
     */
    private fun withRoleResolved(options: LlmOptions): LlmOptions =
        if (options.criteria is ByRoleModelSelectionCriteria) {
            modelProvider.resolveLlmOptions(options)
        } else {
            options
        }

    protected fun chooseLlm(
        llmOptions: LlmOptions,
    ): LlmService<*> {
        val crit: ModelSelectionCriteria = when (llmOptions.criteria) {
            is AutoModelSelectionCriteria ->
                autoLlmSelectionCriteriaResolver.resolveAutoLlm()

            else -> llmOptions.criteria
        }
        if (crit is PreResolvedModelSelectionCriteria<*>) {
            @Suppress("UNCHECKED_CAST")
            return crit.resolved as LlmService<*>
        }
        return modelProvider.getLlm(crit)
    }

    override fun supportsStreaming(options: LlmOptions): Boolean {
        val llmService = chooseLlm(withRoleResolved(options))
        return llmService.supportsStreaming()
    }

    override fun supportsThinking(options: LlmOptions): Boolean {
        val llmService = chooseLlm(withRoleResolved(options))
        return llmService.supportsThinking()
    }

    override fun createStreamingOperations(options: LlmOptions): StreamingLlmOperations {
        // Resolve once and stream with the SAME options. The streamer reads hyperparameters, so
        // resolving only far enough to pick a model would silently drop the tuning a role carries -
        // and streaming is the chat path, where that tuning matters most.
        val resolved = withRoleResolved(options)
        val llmService = chooseLlm(resolved)
        val messageStreamer = llmService.createMessageStreamer(resolved)
        return StreamingLlmOperationsImpl(
            messageStreamer = messageStreamer,
            objectMapper = objectMapper,
            llmService = llmService,
            toolDecorator = toolDecorator,
        )
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
            llmMetadata = chooseLlm(interaction.llm),
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
