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
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.support.AbstractLlmOperations
import com.embabel.agent.core.support.toEmbabelUsage
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmCall
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.agent.spi.support.LlmOperationsPromptsProperties
import com.embabel.agent.spi.toolloop.EmbabelToolLoop
import com.embabel.agent.spi.toolloop.ToolInjectionStrategy
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.spi.validation.ValidationPromptGenerator
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.thinking.ThinkingException
import com.embabel.chat.Message
import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.ModelProvider
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
import org.springframework.context.ApplicationContext
import org.springframework.core.ParameterizedTypeReference
import org.springframework.retry.support.RetrySynchronizationManager
import org.springframework.stereotype.Service
import java.lang.reflect.ParameterizedType
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

const val PROMPT_ELEMENT_SEPARATOR = "\n----\n"

// Log message constants to avoid duplication
private const val LLM_TIMEOUT_MESSAGE = "LLM {}: attempt {} timed out after {}ms"
private const val LLM_INTERRUPTED_MESSAGE = "LLM {}: attempt {} was interrupted"

/**
 * LlmOperations implementation that uses the Spring AI ChatClient
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
    private val llmOperationsPromptsProperties: LlmOperationsPromptsProperties = LlmOperationsPromptsProperties(),
    private val applicationContext: ApplicationContext? = null,
    autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    internal val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val customizers: List<ChatClientCustomizer> = emptyList()
) : AbstractLlmOperations(
    toolDecorator = toolDecorator,
    modelProvider = modelProvider,
    validator = validator,
    validationPromptGenerator = validationPromptGenerator,
    dataBindingProperties = dataBindingProperties,
    autoLlmSelectionCriteriaResolver = autoLlmSelectionCriteriaResolver
) {

    @PostConstruct
    private fun logPropertyConfiguration() {
        val dataBindingFromContext = applicationContext?.runCatching {
            getBeansOfType(LlmDataBindingProperties::class.java).values.firstOrNull()
        }?.getOrNull()

        val promptsFromContext = applicationContext?.runCatching {
            getBeansOfType(LlmOperationsPromptsProperties::class.java).values.firstOrNull()
        }?.getOrNull()

        if (dataBindingFromContext === dataBindingProperties) {
            logger.info("LLM Data Binding: Using Spring-managed properties")
        } else {
            logger.warn("LLM Data Binding: Using fallback defaults")
        }

        if (promptsFromContext === llmOperationsPromptsProperties) {
            logger.info("LLM Prompts: Using Spring-managed properties")
        } else {
            logger.warn("LLM Prompts: Using fallback defaults")
        }

        logger.info(
            "Current LLM settings: maxAttempts=${dataBindingProperties.maxAttempts}, fixedBackoffMillis=${
                dataBindingProperties.fixedBackoffMillis
            }ms, timeout=${
                llmOperationsPromptsProperties.defaultTimeout.seconds
            }s"
        )
    }

    // ====================================
    // NON-THINKING IMPLEMENTATION (uses responseEntity)
    // ====================================

    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        // Check if we should use Embabel's tool loop or Spring AI's internal loop
        return if (interaction.useEmbabelToolLoop) {
            doTransformWithEmbabelToolLoop(messages, interaction, outputClass, llmRequestEvent)
        } else {
            doTransformWithSpringAi(messages, interaction, outputClass, llmRequestEvent)
        }
    }

    /**
     * Transform using Embabel's own tool loop.
     * This gives us control over message history, tool injection, and observability.
     */
    private fun <O> doTransformWithEmbabelToolLoop(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        val llm = chooseLlm(interaction.llm)
        val promptContributions =
            (interaction.promptContributors + llm.promptContributors).joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)

        // Build the output parser based on output class
        val outputParser: (String) -> O = if (outputClass == String::class.java) {
            @Suppress("UNCHECKED_CAST")
            { text -> stringWithoutThinkBlocks(text) as O }
        } else {
            val converter = ExceptionWrappingConverter(
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
            );
            { text -> converter.convert(text)!! }
        }

        // Create the single LLM caller that wraps Spring AI's ChatModel
        val singleLlmCaller = SpringAiSingleLlmCaller(
            chatModel = llm.model,
            chatOptions = chatOptions,
        )

        // Create our tool loop
        val toolLoop = EmbabelToolLoop(
            llmCaller = singleLlmCaller,
            objectMapper = objectMapper,
            injectionStrategy = ToolInjectionStrategy.NONE,
            maxIterations = 20,
        )

        // Build initial messages with prompt contributions
        val initialMessages = buildInitialMessagesForToolLoop(promptContributions, messages)

        // Convert Spring AI ToolCallbacks to Embabel Tools
        val tools = interaction.toolCallbacks.map { it.toEmbabelTool() }

        // Execute the tool loop
        val result = toolLoop.execute(
            initialMessages = initialMessages,
            initialTools = tools,
            outputParser = outputParser,
        )

        return result.result
    }

    /**
     * Adapter to convert a Spring AI ToolCallback to Embabel's Tool interface.
     */
    private fun org.springframework.ai.tool.ToolCallback.toEmbabelTool(): Tool {
        val callback = this
        val definition = callback.toolDefinition
        return object : Tool {
            override val definition: Tool.Definition = Tool.Definition(
                name = definition.name(),
                description = definition.description(),
                inputSchema = Tool.InputSchema.empty(), // Schema is in the callback already
            )

            override fun call(input: String): Tool.Result {
                return try {
                    Tool.Result.text(callback.call(input))
                } catch (e: Exception) {
                    Tool.Result.error(e.message ?: "Tool execution failed", e)
                }
            }
        }
    }

    /**
     * Build initial messages for the tool loop, including system prompt contributions.
     */
    private fun buildInitialMessagesForToolLoop(
        promptContributions: String,
        messages: List<Message>,
    ): List<Message> {
        return if (promptContributions.isNotEmpty()) {
            listOf(com.embabel.chat.SystemMessage(promptContributions)) + messages
        } else {
            messages
        }
    }

    /**
     * Transform using Spring AI's ChatClient with internal tool handling.
     * This is the original implementation preserved for backwards compatibility.
     */
    private fun <O> doTransformWithSpringAi(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        val llm = chooseLlm(interaction.llm)
        val chatClient = createChatClient(llm)
        val promptContributions =
            (interaction.promptContributors + llm.promptContributors).joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }

        val springAiPrompt = buildBasicPrompt(promptContributions, messages)
        llmRequestEvent?.let {
            it.agentProcess.processContext.onProcessEvent(
                it.callEvent(springAiPrompt)
            )
        }

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)
        val timeoutMillis = (interaction.llm.timeout ?: llmOperationsPromptsProperties.defaultTimeout).toMillis()

        return dataBindingProperties.retryTemplate(interaction.id.value).execute<O, DatabindException> {
            val attempt = (RetrySynchronizationManager.getContext()?.retryCount ?: 0) + 1

            val future = CompletableFuture.supplyAsync {
                chatClient
                    .prompt(springAiPrompt)
                    .toolCallbacks(interaction.toolCallbacks)
                    .options(chatOptions)
                    .call()
            }

            val callResponse = try {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS) // NOSONAR: CompletableFuture.get() is not collection access
            } catch (e: Exception) {
                handleFutureException(e, future, interaction, timeoutMillis, attempt)
            }

            if (outputClass == String::class.java) {
                val chatResponse = callResponse.chatResponse()
                chatResponse?.let { recordUsage(llm, it, llmRequestEvent) }
                val rawText = chatResponse!!.result.output.text as String
                stringWithoutThinkBlocks(rawText) as O
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
                re.entity!!
            }
        }
    }

    private fun recordUsage(
        llm: Llm,
        chatResponse: ChatResponse,
        llmRequestEvent: LlmRequestEvent<*>?,
    ) {
        logger.debug("Usage is {}", chatResponse.metadata.usage)
        llmRequestEvent?.let {
            val llmi = LlmInvocation(
                llm = llm,
                usage = chatResponse.metadata.usage.toEmbabelUsage(),
                agentName = it.agentProcess.agent.name,
                timestamp = it.timestamp,
                runningTime = Duration.between(it.timestamp, Instant.now()),
            )
            it.agentProcess.recordLlmInvocation(llmi)
        }
    }

    override fun <O> doTransformIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O> {
        val maybeReturnPromptContribution = templateRenderer.renderLoadedTemplate(
            llmOperationsPromptsProperties.maybePromptTemplate,
            emptyMap(),
        )

        val llm = chooseLlm(interaction.llm)
        val chatClient = createChatClient(llm)
        val promptContributions =
            (interaction.promptContributors + llm.promptContributors).joinToString("\n") { it.contribution() }
        val springAiPrompt = buildPromptWithMaybeReturn(promptContributions, messages, maybeReturnPromptContribution)
        llmRequestEvent.agentProcess.processContext.onProcessEvent(
            llmRequestEvent.callEvent(springAiPrompt)
        )

        val typeReference = createParameterizedTypeReference<MaybeReturn<*>>(
            MaybeReturn::class.java,
            outputClass,
        )
        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)
        val timeoutMillis = (interaction.llm.timeout ?: llmOperationsPromptsProperties.defaultTimeout).toMillis()

        return dataBindingProperties.retryTemplate(interaction.id.value).execute<Result<O>, DatabindException> {
            val attempt = (RetrySynchronizationManager.getContext()?.retryCount ?: 0) + 1

            val callResponse = try {
                CompletableFuture.supplyAsync {
                    chatClient
                        .prompt(springAiPrompt)
                        .toolCallbacks(interaction.toolCallbacks)
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
                    attempt
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
            responseEntity.entity!!.toResult() as Result<O>
        }
    }

    // ====================================
    // THINKING IMPLEMENTATION (manual converter chains)
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
    ): ThinkingResponse<O> {
        logger.debug("LLM transform for interaction {} with thinking extraction", interaction.id.value)

        val llm = chooseLlm(interaction.llm)
        val chatClient = createChatClient(llm)
        val promptContributions =
            (interaction.promptContributors + llm.promptContributors).joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }

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

        llmRequestEvent?.let {
            it.agentProcess.processContext.onProcessEvent(
                it.callEvent(springAiPrompt)
            )
        }

        val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)
        val timeoutMillis = getTimeoutMillis(interaction.llm)

        return dataBindingProperties.retryTemplate(interaction.id.value)
            .execute<ThinkingResponse<O>, DatabindException> {
                val attempt = (RetrySynchronizationManager.getContext()?.retryCount ?: 0) + 1

                val future = CompletableFuture.supplyAsync {
                    chatClient
                        .prompt(springAiPrompt)
                        .toolCallbacks(interaction.toolCallbacks)
                        .options(chatOptions)
                        .call()
                }

                val callResponse = try {
                    future.get(timeoutMillis, TimeUnit.MILLISECONDS) // NOSONAR: CompletableFuture.get() is not collection access
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

                    ThinkingResponse(
                        result = rawText as O, // NOSONAR: Safe cast verified by outputClass == String::class.java check
                        thinkingBlocks = thinkingBlocks
                    )
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

                        ThinkingResponse(
                            result = result!!,
                            thinkingBlocks = thinkingBlocks
                        )
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
    ): Result<ThinkingResponse<O>> {
        return try {
            val maybeReturnPromptContribution = templateRenderer.renderLoadedTemplate(
                llmOperationsPromptsProperties.maybePromptTemplate,
                emptyMap(),
            )

            val llm = chooseLlm(interaction.llm)
            val chatClient = createChatClient(llm)
            val promptContributions =
                (interaction.promptContributors + llm.promptContributors).joinToString("\\n") { it.contribution() }

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

            llmRequestEvent?.agentProcess?.processContext?.onProcessEvent(
                llmRequestEvent.callEvent(springAiPrompt)
            )

            val chatOptions = llm.optionsConverter.convertOptions(interaction.llm)
            val timeoutMillis = (interaction.llm.timeout ?: llmOperationsPromptsProperties.defaultTimeout).toMillis()

            val result = dataBindingProperties.retryTemplate(interaction.id.value)
                .execute<Result<ThinkingResponse<O>>, DatabindException> {
                    val future = CompletableFuture.supplyAsync {
                        chatClient
                            .prompt(springAiPrompt)
                            .toolCallbacks(interaction.toolCallbacks)
                            .options(chatOptions)
                            .call()
                    }

                    val callResponse = try {
                        future.get(timeoutMillis, TimeUnit.MILLISECONDS) // NOSONAR: CompletableFuture.get() is not collection access
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
                        val result = maybeResult!!.toResult() as Result<O> // NOSONAR: Safe cast, MaybeReturn<O>.toResult() returns Result<O>
                        when {
                            result.isSuccess -> Result.success(
                                ThinkingResponse(
                                    result = result.getOrThrow(),
                                    thinkingBlocks = thinkingBlocks
                                )
                            )

                            else -> Result.failure(
                                ThinkingException(
                                    message = "Object creation not possible: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                                    thinkingBlocks = thinkingBlocks
                                )
                            )
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
    // PRIVATE FUNCTIONS
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
    internal fun getLlm(interaction: LlmInteraction): Llm = chooseLlm(interaction.llm)

    /**
     * Create a chat client for the given Embabel Llm definition
     */
    internal fun createChatClient(llm: Llm): ChatClient {
        return ChatClient
            .builder(
                llm.model,
                observationRegistry,
                DefaultChatClientObservationConvention(),
                DefaultAdvisorObservationConvention()
            ).also { builder ->
                customizers.forEach {
                    it.customize(builder)
                }
            }.build()
    }

    private fun shouldGenerateExamples(llmCall: LlmCall): Boolean {
        if (llmOperationsPromptsProperties.generateExamplesByDefault) {
            return llmCall.generateExamples != false
        }
        return llmCall.generateExamples == true
    }

    // ====================================
    // PRIVATE THINKING FUNCTIONS
    // ====================================

    /**
     * Base prompt builder - system message + user messages.
     */
    private fun buildBasicPrompt(
        promptContributions: String,
        messages: List<Message>,
    ): Prompt = Prompt(
        buildList {
            if (promptContributions.isNotEmpty()) {
                add(SystemMessage(promptContributions))
            }
            addAll(messages.map { it.toSpringAiMessage() })
        }
    )

    /**
     * Extends basic prompt with maybeReturn user message.
     */
    private fun buildPromptWithMaybeReturn(
        promptContributions: String,
        messages: List<Message>,
        maybeReturnPrompt: String,
    ): Prompt = Prompt(
        buildList {
            if (promptContributions.isNotEmpty()) {
                add(SystemMessage(promptContributions))
            }
            add(UserMessage(maybeReturnPrompt))
            addAll(messages.map { it.toSpringAiMessage() })
        }
    )

    /**
     * Extends basic prompt with schema format for thinking.
     */
    private fun buildPromptWithSchema(
        promptContributions: String,
        messages: List<Message>,
        schemaFormat: String,
    ): Prompt {
        val basicPrompt = buildBasicPrompt(promptContributions, messages)
        logger.debug("Injected schema format for thinking extraction: {}", schemaFormat)
        return Prompt(
            buildList {
                addAll(basicPrompt.instructions)
                add(SystemMessage(schemaFormat))
            }
        )
    }

    /**
     * Combines maybeReturn user message with schema format.
     */
    private fun buildPromptWithMaybeReturnAndSchema(
        promptContributions: String,
        messages: List<Message>,
        maybeReturnPrompt: String,
        schemaFormat: String,
    ): Prompt {
        val promptWithMaybeReturn = buildPromptWithMaybeReturn(promptContributions, messages, maybeReturnPrompt)
        return Prompt(
            buildList {
                addAll(promptWithMaybeReturn.instructions)
                add(SystemMessage(schemaFormat))
            }
        )
    }

    private fun getTimeoutMillis(llmOptions: com.embabel.common.ai.model.LlmOptions): Long =
        (llmOptions.timeout ?: llmOperationsPromptsProperties.defaultTimeout).toMillis()

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
     * Handles exceptions from CompletableFuture execution during LLM calls, returning Result.failure.
     *
     * Similar to handleFutureException but returns Result.failure with ThinkingException
     * instead of throwing. Used for methods that return Result types rather than throwing exceptions.
     *
     * @param e The exception that occurred during future execution
     * @param future The CompletableFuture to cancel on error
     * @param interaction The LLM interaction context for error messages
     * @param timeoutMillis The timeout value for error reporting
     * @param attempt The retry attempt number for logging
     * @return Result.failure with ThinkingException containing empty thinking blocks
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
                Result.failure(ThinkingException(
                    message = "ChatClient call for interaction ${interaction.id.value} timed out after ${timeoutMillis}ms",
                    thinkingBlocks = emptyList() // No response = no thinking blocks
                ))
            }
            is InterruptedException -> {
                future.cancel(true)
                Thread.currentThread().interrupt()
                logger.warn(LLM_INTERRUPTED_MESSAGE, interaction.id.value, attempt)
                Result.failure(ThinkingException(
                    message = "ChatClient call for interaction ${interaction.id.value} was interrupted",
                    thinkingBlocks = emptyList() // No response = no thinking blocks
                ))
            }
            else -> {
                future.cancel(true)
                logger.error("LLM {}: attempt {} failed", interaction.id.value, attempt, e)
                Result.failure(ThinkingException(
                    message = "ChatClient call for interaction ${interaction.id.value} failed: ${e.message}",
                    thinkingBlocks = emptyList() // No response = no thinking blocks
                ))
            }
        }
    }

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
