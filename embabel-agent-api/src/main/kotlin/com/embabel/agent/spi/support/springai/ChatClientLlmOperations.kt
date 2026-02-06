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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.toEmbabelUsage
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.agent.spi.support.LlmOperationsPromptsProperties
import com.embabel.agent.spi.support.OutputConverter
import com.embabel.agent.spi.support.ToolLoopLlmOperations
import com.embabel.agent.spi.support.ToolResolutionHelper
import com.embabel.agent.spi.support.guardrails.validateAssistantResponse
import com.embabel.agent.spi.support.guardrails.validateUserInput
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.spi.validation.ValidationPromptGenerator
import com.embabel.chat.Message
import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.core.thinking.ThinkingException
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.thinking.spi.InternalThinkingApi
import com.embabel.common.core.thinking.spi.extractAllThinkingBlocks
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.DatabindException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import jakarta.validation.Validator
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientCustomizer
import org.springframework.ai.chat.client.ResponseEntity
import org.springframework.ai.chat.client.advisor.observation.DefaultAdvisorObservationConvention
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.getBeansOfType
import org.springframework.context.ApplicationContext
import org.springframework.core.ParameterizedTypeReference
import org.springframework.retry.support.RetrySynchronizationManager
import org.springframework.stereotype.Service
import java.lang.reflect.ParameterizedType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Log message constants to avoid duplication
private const val LLM_TIMEOUT_MESSAGE = "LLM {}: attempt {} timed out after {}ms"
private const val LLM_INTERRUPTED_MESSAGE = "LLM {}: attempt {} was interrupted"

/**
 * Spring AI implementation of LlmOperations using ChatClient.
 *
 * This class extends [ToolLoopLlmOperations] to inherit the framework-agnostic
 * tool loop implementation, and adds Spring AI-specific functionality:
 * - Spring AI converters for structured output
 * - ChatClient for the legacy Spring AI tool handling path
 * - Spring AI message/prompt building
 *
 * @param modelProvider ModelProvider to get the LLM model
 * @param toolDecorator ToolDecorator to decorate tools to make them aware of platform
 * @param templateRenderer TemplateRenderer to render templates
 * @param dataBindingProperties properties
 */
@Service
internal class ChatClientLlmOperations(
    modelProvider: ModelProvider,
    toolDecorator: ToolDecorator,
    validator: Validator,
    validationPromptGenerator: ValidationPromptGenerator = DefaultValidationPromptGenerator(),
    private val templateRenderer: TemplateRenderer,
    dataBindingProperties: LlmDataBindingProperties = LlmDataBindingProperties(),
    llmOperationsPromptsProperties: LlmOperationsPromptsProperties = LlmOperationsPromptsProperties(),
    private val applicationContext: ApplicationContext? = null,
    autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val customizers: List<ChatClientCustomizer> = emptyList(),
) : ToolLoopLlmOperations(
    toolDecorator = toolDecorator,
    modelProvider = modelProvider,
    validator = validator,
    validationPromptGenerator = validationPromptGenerator,
    dataBindingProperties = dataBindingProperties,
    autoLlmSelectionCriteriaResolver = autoLlmSelectionCriteriaResolver,
    promptsProperties = llmOperationsPromptsProperties,
    objectMapper = objectMapper,
    observationRegistry = observationRegistry,
) {

    @PostConstruct
    private fun logPropertyConfiguration() {
        val dataBindingFromContext = applicationContext?.runCatching {
            getBeansOfType<LlmDataBindingProperties>().values.firstOrNull()
        }?.getOrNull()

        val promptsFromContext = applicationContext?.runCatching {
            getBeansOfType<LlmOperationsPromptsProperties>().values.firstOrNull()
        }?.getOrNull()

        if (dataBindingFromContext === dataBindingProperties) {
            logger.debug("LLM Data Binding: Using Spring-managed properties")
        } else {
            logger.warn("LLM Data Binding: Using fallback defaults")
        }

        if (promptsFromContext === promptsProperties) {
            logger.debug("LLM Prompts: Using Spring-managed properties")
        } else {
            logger.warn("LLM Prompts: Using fallback defaults")
        }

        logger.info(
            "Current LLM settings: maxAttempts={}, fixedBackoffMillis={}ms, timeout={}s",
            dataBindingProperties.maxAttempts,
            dataBindingProperties.fixedBackoffMillis,
            promptsProperties.defaultTimeout.seconds,
        )
    }

    // ====================================
    // OVERRIDES FROM ToolLoopLlmOperations
    // ====================================

    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        // Check if we should use Embabel's tool loop or Spring AI's internal loop
        return if (interaction.useEmbabelToolLoop) {
            super.doTransform(messages, interaction, outputClass, llmRequestEvent)
        } else {
            doTransformWithSpringAi(messages, interaction, outputClass, llmRequestEvent)
        }
    }

    override fun createMessageSender(
        llm: LlmService<*>,
        options: LlmOptions,
    ): LlmMessageSender {
        return llm.createMessageSender(options)
    }

    override fun <O> createOutputConverter(
        outputClass: Class<O>,
        interaction: LlmInteraction,
    ): OutputConverter<O> {
        val springAiConverter = ExceptionWrappingConverter(
            expectedType = outputClass,
            delegate = WithExampleConverter(
                delegate = SuppressThinkingConverter(
                    FilteringJacksonOutputConverter(
                        clazz = outputClass,
                        objectMapper = objectMapper,
                        propertyFilter = interaction.propertyFilter,
                    )
                ),
                outputClass = outputClass,
                ifPossible = false,
                generateExamples = shouldGenerateExamples(interaction),
            )
        )
        return SpringAiOutputConverterAdapter(springAiConverter)
    }

    override fun sanitizeStringOutput(text: String): String {
        return stringWithoutThinkBlocks(text)
    }

    override fun emitCallEvent(
        llmRequestEvent: LlmRequestEvent<*>?,
        promptContributions: String,
        messages: List<Message>,
        schemaFormat: String?,
    ) {
        llmRequestEvent?.let {
            val springAiPrompt = if (schemaFormat != null) {
                buildPromptWithSchema(promptContributions, messages, schemaFormat)
            } else {
                buildBasicPrompt(promptContributions, messages)
            }
            it.agentProcess.processContext.onProcessEvent(
                it.chatModelCallEvent(springAiPrompt)
            )
        }
    }

    override fun <O> doTransformIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O> {
        val maybeReturnPromptContribution = templateRenderer.renderLoadedTemplate(
            promptsProperties.maybePromptTemplate,
            emptyMap(),
        )

        val llm = chooseLlm(interaction.llm)
        val chatClient = createChatClient(llm)
        val promptContributions = buildPromptContributions(interaction, llm)
        val springAiPrompt = buildPromptWithMaybeReturn(promptContributions, messages, maybeReturnPromptContribution)

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<com.embabel.chat.UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent.agentProcess.blackboard)

        llmRequestEvent.agentProcess.processContext.onProcessEvent(
            llmRequestEvent.chatModelCallEvent(springAiPrompt)
        )

        val typeReference = createParameterizedTypeReference<MaybeReturn<*>>(
            MaybeReturn::class.java,
            outputClass,
        )
        val chatOptions = requireSpringAiLlm(llm).optionsConverter.convertOptions(interaction.llm)
        val timeoutMillis = getTimeoutMillis(interaction.llm)

        return dataBindingProperties.retryTemplate(interaction.id.value).execute<Result<O>, DatabindException> {
            val attempt = (RetrySynchronizationManager.getContext()?.retryCount ?: 0) + 1

            val callResponse = try {
                CompletableFuture.supplyAsync {
                    chatClient
                        .prompt(springAiPrompt)
                        .toolCallbacks(interaction.tools.toSpringToolCallbacks())
                        .options(chatOptions)
                        .call()
                }
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .exceptionally { throwable ->
                        when (throwable.cause ?: throwable) {
                            is TimeoutException -> {
                                logger.warn(
                                    LLM_TIMEOUT_MESSAGE,
                                    interaction.id.value,
                                    attempt,
                                    timeoutMillis
                                )
                                throw RuntimeException(
                                    "ChatClient call for interaction ${interaction.id.value} timed out after ${timeoutMillis}ms",
                                    throwable
                                )
                            }

                            is RuntimeException -> {
                                logger.error(
                                    "LLM {}: attempt {} failed",
                                    interaction.id.value,
                                    attempt,
                                    throwable.cause ?: throwable
                                )
                                throw (throwable.cause as? RuntimeException ?: throwable)
                            }

                            else -> {
                                logger.error(
                                    "LLM {}: attempt {} failed with unexpected error",
                                    interaction.id.value,
                                    attempt,
                                    throwable.cause ?: throwable
                                )
                                throw RuntimeException(
                                    "ChatClient call for interaction ${interaction.id.value} failed",
                                    throwable.cause ?: throwable
                                )
                            }
                        }
                    }
                    .get() // NOSONAR: CompletableFuture.get() is not collection access
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn(
                    LLM_INTERRUPTED_MESSAGE,
                    interaction.id.value,
                    attempt,
                )
                throw RuntimeException(
                    "ChatClient call for interaction ${interaction.id.value} was interrupted",
                    e
                )
            }

            val responseEntity: ResponseEntity<ChatResponse, MaybeReturn<*>> = callResponse
                .responseEntity(
                    ExceptionWrappingConverter(
                        expectedType = MaybeReturn::class.java,
                        delegate = WithExampleConverter(
                            delegate = SuppressThinkingConverter(
                                FilteringJacksonOutputConverter(
                                    typeReference = typeReference,
                                    objectMapper = objectMapper,
                                    propertyFilter = interaction.propertyFilter,
                                )
                            ),
                            outputClass = outputClass as Class<MaybeReturn<*>>,
                            ifPossible = true,
                            generateExamples = shouldGenerateExamples(interaction),
                        )
                    )
                )

            responseEntity.response?.let { recordUsage(llm, it, llmRequestEvent) }

            // Guardrails: Post-validation of assistant response
            // Note: When using ResponseEntity, assistant response validation has limited value since
            // the JSON has already been parsed and converted to an object. However, we cannot
            // block users from configuring validators, so we validate the raw JSON string.
            val rawText = responseEntity.response?.result?.output?.text ?: ""
            validateAssistantResponse(rawText, interaction, llmRequestEvent.agentProcess.blackboard)

            responseEntity.entity!!.toResult() as Result<O>
        }
    }

    // ====================================
    // SPRING AI SPECIFIC: LEGACY PATH
    // ====================================

    /**
     * Transform using Spring AI's ChatClient with internal tool handling.
     * This is the original implementation preserved for backwards compatibility.
     *
     * Note: Timeout is handled at the AbstractLlmOperations level via executeWithTimeout(),
     * so this method makes synchronous calls without its own timeout wrapper.
     */
    private fun <O> doTransformWithSpringAi(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        val llm = chooseLlm(interaction.llm)
        val chatClient = createChatClient(llm)
        val promptContributions = buildPromptContributions(interaction, llm)

        val springAiPrompt = buildBasicPrompt(promptContributions, messages)

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<com.embabel.chat.UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)

        llmRequestEvent?.let {
            it.agentProcess.processContext.onProcessEvent(
                it.chatModelCallEvent(springAiPrompt)
            )
        }

        val chatOptions = requireSpringAiLlm(llm).optionsConverter.convertOptions(interaction.llm)

        val callResponse = chatClient
            .prompt(springAiPrompt)
            .toolCallbacks(interaction.tools.toSpringToolCallbacks())
            .options(chatOptions)
            .call()

        return if (outputClass == String::class.java) {
            val chatResponse = callResponse.chatResponse()
            chatResponse?.let { recordUsage(llm, it, llmRequestEvent) }
            val rawText = chatResponse!!.result.output.text as String
            val result = stringWithoutThinkBlocks(rawText) as O

            // Guardrails: Post-validation of assistant response
            validateAssistantResponse(rawText, interaction, llmRequestEvent?.agentProcess?.blackboard)

            result
        } else {
            val re = callResponse.responseEntity(
                ExceptionWrappingConverter(
                    expectedType = outputClass,
                    delegate = WithExampleConverter(
                        delegate = SuppressThinkingConverter(
                            FilteringJacksonOutputConverter(
                                clazz = outputClass,
                                objectMapper = objectMapper,
                                propertyFilter = interaction.propertyFilter,
                            )
                        ),
                        outputClass = outputClass,
                        ifPossible = false,
                        generateExamples = shouldGenerateExamples(interaction),
                    )
                ),
            )
            re.response?.let { recordUsage(llm, it, llmRequestEvent) }
            val result = re.entity!!

            // Guardrails: Post-validation of assistant response
            // Note: When using ResponseEntity, assistant response validation has limited value since
            // the JSON has already been parsed and converted to an object. However, we cannot
            // block users from configuring validators, so we validate the raw JSON string.
            re.response?.result?.output?.text?.let { rawText ->
                validateAssistantResponse(rawText, interaction, llmRequestEvent?.agentProcess?.blackboard)
            }

            result
        }
    }

    // ====================================
    // THINKING IMPLEMENTATION
    // ====================================

    /**
     * Transform messages to an object with thinking block extraction.
     */
    @OptIn(InternalThinkingApi::class)
    internal fun <O> doTransformWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
        agentProcess: AgentProcess?,
        action: Action?,
    ): ThinkingResponse<O> {
        logger.debug("LLM transform for interaction {} with thinking extraction", interaction.id.value)

        val llm = chooseLlm(interaction.llm)
        val chatClient = createChatClient(llm)
        val promptContributions = buildPromptContributions(interaction, llm)

        // Create converter chain once for both schema format and actual conversion
        val converter = if (outputClass != String::class.java) {
            ExceptionWrappingConverter(
                expectedType = outputClass,
                delegate = WithExampleConverter(
                    delegate = SuppressThinkingConverter(
                        FilteringJacksonOutputConverter(
                            clazz = outputClass,
                            objectMapper = objectMapper,
                            propertyFilter = interaction.propertyFilter,
                        )
                    ),
                    outputClass = outputClass,
                    ifPossible = false,
                    generateExamples = shouldGenerateExamples(interaction),
                )
            )
        } else null

        val schemaFormat = converter?.getFormat()

        val springAiPrompt = if (schemaFormat != null) {
            buildPromptWithSchema(promptContributions, messages, schemaFormat)
        } else {
            buildBasicPrompt(promptContributions, messages)
        }

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<com.embabel.chat.UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)

        llmRequestEvent?.let {
            it.agentProcess.processContext.onProcessEvent(
                it.chatModelCallEvent(springAiPrompt)
            )
        }

        val chatOptions = requireSpringAiLlm(llm).optionsConverter.convertOptions(interaction.llm)
        val timeoutMillis = getTimeoutMillis(interaction.llm)

        // Resolve tool groups and decorate tools
        val tools = resolveAndDecorateTools(interaction, agentProcess, action)

        return dataBindingProperties.retryTemplate(interaction.id.value)
            .execute<ThinkingResponse<O>, DatabindException> {
                val attempt = (RetrySynchronizationManager.getContext()?.retryCount ?: 0) + 1

                val future = CompletableFuture.supplyAsync {
                    chatClient
                        .prompt(springAiPrompt)
                        .toolCallbacks(tools.toSpringToolCallbacks())
                        .options(chatOptions)
                        .call()
                }

                val callResponse = try {
                    future.get(
                        timeoutMillis,
                        TimeUnit.MILLISECONDS
                    ) // NOSONAR: CompletableFuture.get() is not collection access
                } catch (e: Exception) {
                    handleFutureException(e, future, interaction, timeoutMillis, attempt)
                }

                logger.debug("LLM call completed for interaction {}", interaction.id.value)

                // Convert response with thinking extraction using manual converter chains
                if (outputClass == String::class.java) {
                    val chatResponse = callResponse.chatResponse()
                    chatResponse?.let { recordUsage(llm, it, llmRequestEvent) }
                    val rawText = chatResponse!!.result.output.text as String

                    val thinkingBlocks = extractAllThinkingBlocks(rawText)
                    logger.debug("Extracted {} thinking blocks for String response", thinkingBlocks.size)

                    val thinkingResponse = ThinkingResponse(
                        result = rawText as O, // NOSONAR: Safe cast verified by outputClass == String::class.java check
                        thinkingBlocks = thinkingBlocks
                    )

                    // Guardrails: Post-validation of assistant response (ThinkingResponse with thinking blocks)
                    validateAssistantResponse(thinkingResponse, interaction, llmRequestEvent?.agentProcess?.blackboard)

                    thinkingResponse
                } else {
                    // Extract thinking blocks from raw response text FIRST
                    val chatResponse = callResponse.chatResponse()
                    chatResponse?.let { recordUsage(llm, it, llmRequestEvent) }
                    val rawText = chatResponse!!.result.output.text ?: ""

                    val thinkingBlocks = extractAllThinkingBlocks(rawText)
                    logger.debug(
                        "Extracted {} thinking blocks for {} response",
                        thinkingBlocks.size,
                        outputClass.simpleName
                    )

                    // Execute converter chain manually instead of using responseEntity
                    try {
                        val result = converter!!.convert(rawText)

                        val thinkingResponse: ThinkingResponse<O> = ThinkingResponse(
                            result = result!! as O,
                            thinkingBlocks = thinkingBlocks
                        )

                        // Guardrails: Post-validation of assistant response (ThinkingResponse with thinking blocks)
                        validateAssistantResponse(
                            thinkingResponse,
                            interaction,
                            llmRequestEvent?.agentProcess?.blackboard
                        )

                        thinkingResponse
                    } catch (e: Exception) {
                        // Preserve thinking blocks in exceptions
                        throw ThinkingException(
                            message = "Conversion failed: ${e.message}",
                            thinkingBlocks = thinkingBlocks
                        )
                    }
                }
            }
    }

    /**
     * Transform messages with thinking extraction using IfPossible pattern.
     */
    @OptIn(InternalThinkingApi::class)
    internal fun <O> doTransformWithThinkingIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
        agentProcess: AgentProcess?,
        action: Action?,
    ): Result<ThinkingResponse<O>> {
        return try {
            val maybeReturnPromptContribution = templateRenderer.renderLoadedTemplate(
                promptsProperties.maybePromptTemplate,
                emptyMap(),
            )

            val llm = chooseLlm(interaction.llm)
            val chatClient = createChatClient(llm)
            val promptContributions = buildPromptContributions(interaction, llm)

            val typeReference = createParameterizedTypeReference<MaybeReturn<*>>(
                MaybeReturn::class.java,
                outputClass,
            )

            // Create converter chain BEFORE LLM call to get schema format
            val converter = ExceptionWrappingConverter(
                expectedType = MaybeReturn::class.java,
                delegate = WithExampleConverter(
                    delegate = SuppressThinkingConverter(
                        FilteringJacksonOutputConverter(
                            typeReference = typeReference,
                            objectMapper = objectMapper,
                            propertyFilter = interaction.propertyFilter,
                        )
                    ),
                    outputClass = outputClass as Class<MaybeReturn<*>>, // NOSONAR: Safe cast for MaybeReturn wrapper pattern
                    ifPossible = true,
                    generateExamples = shouldGenerateExamples(interaction),
                )
            )

            // Get the complete format (examples + JSON schema)
            val schemaFormat = converter.getFormat()

            val springAiPrompt = buildPromptWithMaybeReturnAndSchema(
                promptContributions,
                messages,
                maybeReturnPromptContribution,
                schemaFormat
            )

            // Guardrails: Pre-validation of user input
            val userMessages = messages.filterIsInstance<com.embabel.chat.UserMessage>()
            validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)

            llmRequestEvent?.agentProcess?.processContext?.onProcessEvent(
                llmRequestEvent.chatModelCallEvent(springAiPrompt)
            )

            val chatOptions = requireSpringAiLlm(llm).optionsConverter.convertOptions(interaction.llm)
            val timeoutMillis = getTimeoutMillis(interaction.llm)

            // Resolve tool groups and decorate tools
            val tools = resolveAndDecorateTools(interaction, agentProcess, action)

            val result = dataBindingProperties.retryTemplate(interaction.id.value)
                .execute<Result<ThinkingResponse<O>>, DatabindException> {
                    val future = CompletableFuture.supplyAsync {
                        chatClient
                            .prompt(springAiPrompt)
                            .toolCallbacks(tools.toSpringToolCallbacks())
                            .options(chatOptions)
                            .call()
                    }

                    val callResponse = try {
                        future.get(
                            timeoutMillis,
                            TimeUnit.MILLISECONDS
                        ) // NOSONAR: CompletableFuture.get() is not collection access
                    } catch (e: Exception) {
                        val attempt = (RetrySynchronizationManager.getContext()?.retryCount ?: 0) + 1
                        return@execute handleFutureExceptionAsResult(e, future, interaction, timeoutMillis, attempt)
                    }

                    // Extract thinking blocks from raw text FIRST
                    val chatResponse = callResponse.chatResponse()
                    chatResponse?.let { recordUsage(llm, it, llmRequestEvent) }
                    val rawText = chatResponse!!.result.output.text ?: ""
                    val thinkingBlocks = extractAllThinkingBlocks(rawText)

                    // Execute converter chain manually instead of using responseEntity
                    try {
                        val maybeResult = converter.convert(rawText)

                        // Convert MaybeReturn<O> to Result<ThinkingResponse<O>> with extracted thinking blocks
                        val result =
                            maybeResult!!.toResult() as Result<O> // NOSONAR: Safe cast, MaybeReturn<O>.toResult() returns Result<O>
                        when {
                            result.isSuccess -> {
                                val thinkingResponse = ThinkingResponse(
                                    result = result.getOrThrow(),
                                    thinkingBlocks = thinkingBlocks
                                )

                                // Guardrails: Post-validation of assistant response (ThinkingResponse with thinking blocks)
                                validateAssistantResponse(
                                    thinkingResponse,
                                    interaction,
                                    llmRequestEvent?.agentProcess?.blackboard
                                )

                                Result.success(thinkingResponse)
                            }

                            else -> {
                                // Validate thinking blocks even when object creation fails
                                if (thinkingBlocks.isNotEmpty()) {
                                    val thinkingResponse = ThinkingResponse(
                                        result = null,
                                        thinkingBlocks = thinkingBlocks
                                    )
                                    validateAssistantResponse(
                                        thinkingResponse,
                                        interaction,
                                        llmRequestEvent?.agentProcess?.blackboard
                                    )
                                }

                                Result.failure(
                                    ThinkingException(
                                        message = "Object creation not possible: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                                        thinkingBlocks = thinkingBlocks
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Other failures, preserve thinking blocks
                        Result.failure(
                            ThinkingException(
                                message = "Conversion failed: ${e.message}",
                                thinkingBlocks = thinkingBlocks
                            )
                        )
                    }
                }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ====================================
    // SPRING AI SPECIFIC UTILITIES
    // ====================================

    @Suppress("UNCHECKED_CAST")
    private fun <T> createParameterizedTypeReference(
        rawType: Class<*>,
        typeArgument: Class<*>,
    ): ParameterizedTypeReference<T> {
        // Create a type with proper generic information
        val type = object : ParameterizedType {
            override fun getRawType() = rawType
            override fun getActualTypeArguments() = arrayOf(typeArgument)
            override fun getOwnerType() = null
        }

        // Create a ParameterizedTypeReference that uses our custom type
        return object : ParameterizedTypeReference<T>() {
            override fun getType() = type
        }
    }

    /**
     * Expose LLM selection for streaming operations
     */
    internal fun getLlm(interaction: LlmInteraction): LlmService<*> = chooseLlm(interaction.llm)

    /**
     * Require the LLM to be a SpringAiLlm for Spring AI specific operations.
     */
    private fun requireSpringAiLlm(llm: LlmService<*>): SpringAiLlmService {
        return llm as? SpringAiLlmService
            ?: throw IllegalStateException("ChatClientLlmOperations requires SpringAiLlm, got ${llm::class.simpleName}")
    }

    /**
     * Create a chat client for the given LlmService.
     * Requires the LlmService to be a SpringAiLlm.
     */
    internal fun createChatClient(llm: LlmService<*>): ChatClient {
        val springAiLlm = requireSpringAiLlm(llm)
        return ChatClient
            .builder(
                springAiLlm.chatModel,
                observationRegistry,
                DefaultChatClientObservationConvention(),
                DefaultAdvisorObservationConvention()
            ).also { builder ->
                customizers.forEach {
                    it.customize(builder)
                }
            }.build()
    }

    private fun recordUsage(
        llm: LlmService<*>,
        chatResponse: ChatResponse,
        llmRequestEvent: LlmRequestEvent<*>?,
    ) {
        logger.debug("Usage is {}", chatResponse.metadata.usage)
        recordUsage(llm, chatResponse.metadata.usage.toEmbabelUsage(), llmRequestEvent)
    }

    // ====================================
    // SPRING AI PROMPT BUILDERS
    // ====================================

    /**
     * Base prompt builder - consolidates all system messages at the beginning.
     * Extracts SystemMessages from the input messages and merges with promptContributions
     * to ensure proper message ordering for cross-model compatibility.
     */
    private fun buildBasicPrompt(
        promptContributions: String,
        messages: List<Message>,
    ): Prompt {
        val (systemMessages, nonSystemMessages) = partitionMessages(messages)
        val allSystemContent = buildConsolidatedSystemMessage(promptContributions, *systemMessages.toTypedArray())
        return Prompt(
            buildList {
                if (allSystemContent.isNotEmpty()) {
                    add(SystemMessage(allSystemContent))
                }
                addAll(nonSystemMessages.map { it.toSpringAiMessage() })
            }
        )
    }

    /**
     * Extends basic prompt with maybeReturn user message.
     * Consolidates all system messages at the beginning for proper ordering.
     */
    private fun buildPromptWithMaybeReturn(
        promptContributions: String,
        messages: List<Message>,
        maybeReturnPrompt: String,
    ): Prompt {
        val (systemMessages, nonSystemMessages) = partitionMessages(messages)
        val allSystemContent = buildConsolidatedSystemMessage(promptContributions, *systemMessages.toTypedArray())
        return Prompt(
            buildList {
                if (allSystemContent.isNotEmpty()) {
                    add(SystemMessage(allSystemContent))
                }
                add(UserMessage(maybeReturnPrompt))
                addAll(nonSystemMessages.map { it.toSpringAiMessage() })
            }
        )
    }

    /**
     * Builds a prompt with schema format consolidated into the system message.
     * All system content is placed at the beginning to ensure proper message ordering
     * for cross-model compatibility (OpenAI best practice, required by DeepSeek, etc.).
     */
    private fun buildPromptWithSchema(
        promptContributions: String,
        messages: List<Message>,
        schemaFormat: String,
    ): Prompt {
        logger.debug("Injected schema format for thinking extraction: {}", schemaFormat)
        val (systemMessages, nonSystemMessages) = partitionMessages(messages)
        val allSystemContent = buildConsolidatedSystemMessage(
            promptContributions,
            *systemMessages.toTypedArray(),
            schemaFormat
        )
        return Prompt(
            buildList {
                if (allSystemContent.isNotEmpty()) {
                    add(SystemMessage(allSystemContent))
                }
                addAll(nonSystemMessages.map { it.toSpringAiMessage() })
            }
        )
    }

    /**
     * Combines maybeReturn user message with schema format.
     * All system content is consolidated at the beginning for proper message ordering.
     */
    private fun buildPromptWithMaybeReturnAndSchema(
        promptContributions: String,
        messages: List<Message>,
        maybeReturnPrompt: String,
        schemaFormat: String,
    ): Prompt {
        val (systemMessages, nonSystemMessages) = partitionMessages(messages)
        val allSystemContent = buildConsolidatedSystemMessage(
            promptContributions,
            *systemMessages.toTypedArray(),
            schemaFormat
        )
        return Prompt(
            buildList {
                if (allSystemContent.isNotEmpty()) {
                    add(SystemMessage(allSystemContent))
                }
                add(UserMessage(maybeReturnPrompt))
                addAll(nonSystemMessages.map { it.toSpringAiMessage() })
            }
        )
    }

    /**
     * Partitions messages into system messages (content only) and non-system messages.
     * This enables consolidating all system content at the beginning of the prompt.
     */
    private fun partitionMessages(messages: List<Message>): Pair<List<String>, List<Message>> {
        val systemContent = mutableListOf<String>()
        val nonSystemMessages = mutableListOf<Message>()
        for (message in messages) {
            if (message is com.embabel.chat.SystemMessage) {
                systemContent.add(message.content)
            } else {
                nonSystemMessages.add(message)
            }
        }
        return systemContent to nonSystemMessages
    }

    /**
     * Consolidates multiple system content strings into a single system message.
     * This ensures a single system message at the beginning of the conversation,
     * following OpenAI best practices and ensuring compatibility with models like DeepSeek
     * that have strict message ordering requirements.
     */
    private fun buildConsolidatedSystemMessage(vararg contents: String): String =
        contents.filter { it.isNotEmpty() }.joinToString("\n\n")

    // ====================================
    // EXCEPTION HANDLING
    // ====================================

    /**
     * Handles exceptions from CompletableFuture execution during LLM calls.
     *
     * Provides centralized exception handling for timeout, interruption, and execution failures.
     * Cancels the future, logs appropriate warnings/errors, and throws descriptive RuntimeExceptions.
     *
     * @param e The exception that occurred during future execution
     * @param future The CompletableFuture to cancel on error
     * @param interaction The LLM interaction context for error messages
     * @param timeoutMillis The timeout value for error reporting
     * @param attempt The retry attempt number for logging
     * @throws RuntimeException Always throws with appropriate error message based on exception type
     */
    private fun handleFutureException(
        e: Exception,
        future: CompletableFuture<*>,
        interaction: LlmInteraction,
        timeoutMillis: Long,
        attempt: Int
    ): Nothing {
        when (e) {
            is TimeoutException -> {
                future.cancel(true)
                logger.warn(LLM_TIMEOUT_MESSAGE, interaction.id.value, attempt, timeoutMillis)
                throw RuntimeException(
                    "ChatClient call for interaction ${interaction.id.value} timed out after ${timeoutMillis}ms",
                    e
                )
            }

            is InterruptedException -> {
                future.cancel(true)
                Thread.currentThread().interrupt()
                logger.warn(LLM_INTERRUPTED_MESSAGE, interaction.id.value, attempt)
                throw RuntimeException("ChatClient call for interaction ${interaction.id.value} was interrupted", e)
            }

            is ExecutionException -> {
                future.cancel(true)
                logger.error(
                    "LLM {}: attempt {} failed with execution exception",
                    interaction.id.value,
                    attempt,
                    e.cause
                )
                when (val cause = e.cause) {
                    is RuntimeException -> throw cause
                    is Exception -> throw RuntimeException(
                        "ChatClient call for interaction ${interaction.id.value} failed",
                        cause
                    )

                    else -> throw RuntimeException(
                        "ChatClient call for interaction ${interaction.id.value} failed with unknown error",
                        e
                    )
                }
            }

            else -> throw e
        }
    }

    /**
     * Handles exceptions from CompletableFuture execution, returning Result.failure.
     */
    private fun <O> handleFutureExceptionAsResult(
        e: Exception,
        future: CompletableFuture<*>,
        interaction: LlmInteraction,
        timeoutMillis: Long,
        attempt: Int
    ): Result<ThinkingResponse<O>> {
        return when (e) {
            is TimeoutException -> {
                future.cancel(true)
                logger.warn(LLM_TIMEOUT_MESSAGE, interaction.id.value, attempt, timeoutMillis)
                Result.failure(
                    ThinkingException(
                        message = "ChatClient call for interaction ${interaction.id.value} timed out after ${timeoutMillis}ms",
                        thinkingBlocks = emptyList()
                    )
                )
            }

            is InterruptedException -> {
                future.cancel(true)
                Thread.currentThread().interrupt()
                logger.warn(LLM_INTERRUPTED_MESSAGE, interaction.id.value, attempt)
                Result.failure(
                    ThinkingException(
                        message = "ChatClient call for interaction ${interaction.id.value} was interrupted",
                        thinkingBlocks = emptyList() // No response = no thinking blocks
                    )
                )
            }

            else -> {
                future.cancel(true)
                logger.error("LLM {}: attempt {} failed", interaction.id.value, attempt, e)
                Result.failure(
                    ThinkingException(
                        message = "ChatClient call for interaction ${interaction.id.value} failed: ${e.message}",
                        thinkingBlocks = emptyList() // No response = no thinking blocks
                    )
                )
            }
        }
    }

    // ====================================
    // TOOL RESOLUTION
    // ====================================

    /**
     * Resolves ToolGroups and decorates all tools for streaming operations.
     * Convenience wrapper around [ToolResolutionHelper.resolveAndDecorate].
     * When agentProcess is null, returns interaction.tools without decoration.
     */
    internal fun resolveAndDecorateTools(
        interaction: LlmInteraction,
        agentProcess: AgentProcess?,
        action: Action?,
    ): List<Tool> = ToolResolutionHelper.resolveAndDecorate(
        interaction = interaction,
        agentProcess = agentProcess,
        action = action,
        toolDecorator = toolDecorator,
    )
}

/**
 * Adapter to wrap Spring AI's StructuredOutputConverter as our OutputConverter.
 */
private class SpringAiOutputConverterAdapter<T>(
    private val delegate: org.springframework.ai.converter.StructuredOutputConverter<T>,
) : OutputConverter<T> {
    override fun convert(source: String): T? = delegate.convert(source)
    override fun getFormat(): String? = delegate.format
}

/**
 * Structure to be returned by the LLM.
 * Allows the LLM to return a result structure, under success, or an error message
 * One of success or failure must be set, but not both.
 */
internal data class MaybeReturn<T>(
    val success: T? = null,
    val failure: String? = null,
) {

    fun toResult(): Result<T> {
        return if (success != null) {
            Result.success(success)
        } else {
            Result.failure(Exception(failure))
        }
    }
}
