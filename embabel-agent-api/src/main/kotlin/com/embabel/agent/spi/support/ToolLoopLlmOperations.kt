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
import com.embabel.agent.api.event.ToolLoopStartEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.LlmCall
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.ChainedToolInjectionStrategy
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.ToolLoopFactory
import com.embabel.agent.spi.support.guardrails.validateAssistantResponse
import com.embabel.agent.spi.support.guardrails.validateUserInput
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.spi.validation.ValidationPromptGenerator
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.core.thinking.ThinkingException
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.thinking.spi.InternalThinkingApi
import com.embabel.common.core.thinking.spi.extractAllThinkingBlocks
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.observation.ObservationRegistry
import jakarta.validation.Validator
import java.time.Duration
import java.time.Instant

const val PROMPT_ELEMENT_SEPARATOR = "\n----\n"

/**
 * Output converter abstraction for parsing LLM output.
 * Framework-agnostic interface that can be implemented by Spring AI converters or others.
 *
 * @param T the output type
 */
interface OutputConverter<T> {
    /**
     * Convert the LLM output string to the target type.
     */
    fun convert(source: String): T?

    /**
     * Get the format instructions to include in the prompt.
     * Returns null if no format instructions are needed (e.g., for String output).
     */
    fun getFormat(): String?
}

/**
 * LlmOperations implementation that uses Embabel's framework-agnostic tool loop.
 *
 * This class provides the core tool loop orchestration logic without depending on
 * any specific LLM framework (Spring AI, LangChain4j, etc.). Subclasses provide
 * the framework-specific implementations for message sending and output conversion.
 *
 * @param modelProvider ModelProvider to get the LLM model
 * @param toolDecorator ToolDecorator to decorate tools
 * @param validator Validator for bean validation
 * @param validationPromptGenerator Generator for validation prompts
 * @param dataBindingProperties Properties for data binding configuration
 * @param autoLlmSelectionCriteriaResolver Resolver for auto LLM selection
 * @param promptsProperties Properties for prompt configuration
 * @param objectMapper ObjectMapper for JSON serialization
 * @param observationRegistry Registry for distributed tracing observations
 * @param templateRenderer TemplateRenderer for rendering prompt templates (default: NoOpTemplateRenderer)
 */
open class ToolLoopLlmOperations(
    modelProvider: ModelProvider,
    toolDecorator: ToolDecorator,
    validator: Validator,
    validationPromptGenerator: ValidationPromptGenerator = DefaultValidationPromptGenerator(),
    dataBindingProperties: LlmDataBindingProperties = LlmDataBindingProperties(),
    autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    promptsProperties: LlmOperationsPromptsProperties = LlmOperationsPromptsProperties(),
    internal open val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
    protected val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    asyncer: Asyncer = ExecutorAsyncer(java.util.concurrent.Executors.newCachedThreadPool()),
    protected val toolLoopFactory: ToolLoopFactory = ToolLoopFactory.create(ToolLoopConfiguration(), asyncer),
    protected val templateRenderer: TemplateRenderer = NoOpTemplateRenderer,
) : AbstractLlmOperations(
    toolDecorator = toolDecorator,
    modelProvider = modelProvider,
    validator = validator,
    validationPromptGenerator = validationPromptGenerator,
    dataBindingProperties = dataBindingProperties,
    autoLlmSelectionCriteriaResolver = autoLlmSelectionCriteriaResolver,
    promptsProperties = promptsProperties,
    asyncer = asyncer,
) {

    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        val llm = chooseLlm(interaction.llm)
        val promptContributions = buildPromptContributions(interaction, llm)

        val messageSender = createMessageSender(llm, interaction.llm, llmRequestEvent)

        val converter = if (outputClass != String::class.java) {
            createOutputConverter(outputClass, interaction)
        } else null

        val schemaFormat = converter?.getFormat()

        val outputParser: (String) -> O = if (outputClass == String::class.java) {
            @Suppress("UNCHECKED_CAST")
            { text -> sanitizeStringOutput(text) as O }
        } else {
            { text -> converter!!.convert(text)!! }
        }

        val injectedToolDecorator = createInjectedToolDecorator(llmRequestEvent, interaction)
        val injectionStrategy = createInjectionStrategy(interaction)

        val toolLoop = toolLoopFactory.create(
            llmMessageSender = messageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = interaction.maxToolIterations,
            toolDecorator = injectedToolDecorator,
            inspectors = interaction.inspectors,
            transformers = interaction.transformers,
        )

        val initialMessages = buildInitialMessages(promptContributions, messages, schemaFormat)

        emitCallEvent(llmRequestEvent, promptContributions, messages, schemaFormat)

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<com.embabel.chat.UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)

        val tools = interaction.tools
        val toolLoopStartEvent = publishToolLoopStartEvent(llmRequestEvent, tools, interaction, outputClass)

        val result = toolLoop.execute(
            initialMessages = initialMessages,
            initialTools = tools,
            outputParser = outputParser,
        )

        handleToolLoopCompletion(toolLoopStartEvent, result, llmRequestEvent, llm)

        // Guardrails: Post-validation of assistant response
        // For the tool loop path, validate the final result based on its type
        val finalResult = result.result
        when (finalResult) {
            is String -> validateAssistantResponse(finalResult, interaction, llmRequestEvent?.agentProcess?.blackboard)
            is AssistantMessage -> validateAssistantResponse(
                finalResult,
                interaction,
                llmRequestEvent?.agentProcess?.blackboard
            )
            // For other object types, we don't have the raw response text to validate
            // but guardrails could be extended to validate structured objects in the future
        }

        return finalResult
    }

    override fun <O> doTransformIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O> {
        val llm = chooseLlm(interaction.llm)
        val promptContributions = buildPromptContributions(interaction, llm)

        val messageSender = createMessageSender(llm, interaction.llm, llmRequestEvent)

        val converter = createMaybeReturnOutputConverter(outputClass, interaction)!!

        val schemaFormat = converter.getFormat()

        val outputParser: (String) -> MaybeReturn<O> = { text ->
            if (text.isNotBlank()) converter.convert(text)!! else MaybeReturn.noOutput()
        }

        // Create a decorator for dynamically injected tools (e.g., from MatryoshkaTool)
        val injectedToolDecorator: ((Tool) -> Tool) = { tool: Tool ->
            toolDecorator.decorate(
                tool = tool,
                agentProcess = llmRequestEvent.agentProcess,
                action = llmRequestEvent.action,
                llmOptions = interaction.llm,
            )
        }

        val injectionStrategy = if (interaction.additionalInjectionStrategies.isNotEmpty()) {
            ChainedToolInjectionStrategy(
                listOf(ToolInjectionStrategy.DEFAULT) + interaction.additionalInjectionStrategies
            )
        } else {
            ToolInjectionStrategy.DEFAULT
        }

        val toolLoop = toolLoopFactory.create(
            llmMessageSender = messageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = interaction.maxToolIterations,
            toolDecorator = injectedToolDecorator,
            inspectors = interaction.inspectors,
            transformers = interaction.transformers,
        )

        // Build MaybeReturn prompt contribution
        val maybeReturnPromptContribution = templateRenderer.renderLoadedTemplate(
            promptsProperties.maybePromptTemplate,
            emptyMap(),
        )

        val initialMessages = buildInitialMessagesWithMaybeReturn(
            promptContributions,
            messages,
            maybeReturnPromptContribution,
            schemaFormat,
        )

        emitCallEvent(llmRequestEvent, promptContributions, messages, schemaFormat)

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<com.embabel.chat.UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent.agentProcess.blackboard)

        val tools = interaction.tools

        // Publish ToolLoopStartEvent before the tool loop
        val toolLoopStartEvent = ToolLoopStartEvent(
            agentProcess = llmRequestEvent.agentProcess,
            action = llmRequestEvent.action,
            toolNames = tools.map { it.definition.name },
            maxIterations = interaction.maxToolIterations,
            interactionId = interaction.id.value,
            outputClass = outputClass,
        ).also { startEvent ->
            llmRequestEvent.agentProcess.processContext.onProcessEvent(startEvent)
        }

        val result = toolLoop.execute(
            initialMessages = initialMessages,
            initialTools = tools,
            outputParser = outputParser,
        )

        // Publish ToolLoopCompletedEvent after the tool loop
        llmRequestEvent.agentProcess.processContext.onProcessEvent(
            toolLoopStartEvent.completedEvent(
                totalIterations = result.totalIterations,
                replanRequested = result.replanRequested,
            )
        )

        result.totalUsage?.let { usage ->
            recordUsage(llm, usage, llmRequestEvent)
        }

        // If replan was requested, re-throw the exception to propagate to action executor
        if (result.replanRequested) {
            throw ReplanRequestedException(
                reason = result.replanReason ?: "Tool requested replan",
                blackboardUpdater = result.blackboardUpdater,
            )
        }

        // ToolLoopResult.result is non-nullable by design - if tool loop completes, it has a result
        val maybeReturn = result.result

        // Guardrails: Post-validation of assistant response
        // For MaybeReturn, validate the success value if it's a validatable type
        when (val successValue = maybeReturn.success) {
            is String -> validateAssistantResponse(successValue, interaction, llmRequestEvent.agentProcess.blackboard)
            is AssistantMessage -> validateAssistantResponse(successValue, interaction, llmRequestEvent.agentProcess.blackboard)
            // For other object types, we don't have the raw response text to validate
        }

        // Convert MaybeReturn<O> to Result<O>
        return maybeReturn.toResult()
    }

    @OptIn(InternalThinkingApi::class)
    override fun <O> doTransformWithThinking(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): ThinkingResponse<O> {
        val llm = chooseLlm(interaction.llm)
        val promptContributions = buildPromptContributions(interaction, llm)

        val messageSender = createMessageSender(llm, interaction.llm, llmRequestEvent)

        val converter = if (outputClass != String::class.java) {
            createOutputConverter(outputClass, interaction)
        } else null

        val schemaFormat = converter?.getFormat()

        // Output parser that extracts thinking blocks and parses the result
        // For String output: return raw text (with thinking tags preserved)
        // For other types: converter chain handles thinking suppression for JSON parsing
        val outputParser: (String) -> ThinkingResponse<O> = { text ->
            val thinkingBlocks = extractAllThinkingBlocks(text)
            val result = if (outputClass == String::class.java) {
                @Suppress("UNCHECKED_CAST")
                text as O  // Raw text, not sanitized - thinking blocks preserved in response
            } else {
                try {
                    converter!!.convert(text)!!
                } catch (e: Exception) {
                    // Preserve thinking blocks in exceptions
                    throw ThinkingException(
                        message = "Conversion failed: ${e.message}",
                        thinkingBlocks = thinkingBlocks
                    )
                }
            }
            ThinkingResponse(result, thinkingBlocks)
        }

        val injectedToolDecorator = createInjectedToolDecorator(llmRequestEvent, interaction)
        val injectionStrategy = createInjectionStrategy(interaction)

        val toolLoop = toolLoopFactory.create(
            llmMessageSender = messageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = interaction.maxToolIterations,
            toolDecorator = injectedToolDecorator,
            inspectors = interaction.inspectors,
            transformers = interaction.transformers,
        )

        val initialMessages = buildInitialMessages(promptContributions, messages, schemaFormat)

        emitCallEvent(llmRequestEvent, promptContributions, messages, schemaFormat)

        // Guardrails: Pre-validation of user input
        val userMessages = messages.filterIsInstance<com.embabel.chat.UserMessage>()
        validateUserInput(userMessages, interaction, llmRequestEvent?.agentProcess?.blackboard)

        val tools = interaction.tools
        val toolLoopStartEvent = publishToolLoopStartEvent(llmRequestEvent, tools, interaction, outputClass)

        val result = toolLoop.execute(
            initialMessages = initialMessages,
            initialTools = tools,
            outputParser = outputParser,
        )

        handleToolLoopCompletion(toolLoopStartEvent, result, llmRequestEvent, llm)

        val thinkingResponse = result.result

        // Guardrails: Post-validation of assistant response (includes thinking blocks)
        validateAssistantResponse(thinkingResponse, interaction, llmRequestEvent?.agentProcess?.blackboard)

        return thinkingResponse
    }

    override fun <O> doTransformWithThinkingIfPossible(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): Result<ThinkingResponse<O>> {
        // Critical implementation requirements:
        // 1. LLM can't create object → return thinking blocks (if any)
        // 2. Thinking block extraction fails → wrap exception with captured thinking (could be empty)
        TODO("Use OperationContextDelegate path for now")
    }

    /**
     * Create an LlmMessageSender for the given LLM and options.
     * Subclasses implement this to provide framework-specific message senders.
     *
     * @param llm The LLM service to use
     * @param options The LLM options
     * @param llmRequestEvent Optional domain context for instrumentation.
     *        When present, subclasses may use this to wrap the underlying model
     *        for observability (e.g., emitting events with the final prompt).
     * @return A framework-agnostic message sender
     */
    protected open fun createMessageSender(
        llm: LlmService<*>,
        options: LlmOptions,
        llmRequestEvent: LlmRequestEvent<*>? = null,
    ): LlmMessageSender {
        return llm.createMessageSender(options)
    }

    /**
     * Create an output converter for the given output class.
     * Subclasses implement this to provide framework-specific converters.
     *
     * @param outputClass The target output class
     * @param interaction The LLM interaction context
     * @return An output converter, or null for String output
     */
    protected open fun <O> createOutputConverter(
        outputClass: Class<O>,
        interaction: LlmInteraction,
    ): OutputConverter<O>? {
        // Default implementation returns null - subclasses should override
        return null
    }

    /**
     * Create an output converter for MaybeReturn wrapper type.
     * Used by doTransformIfPossible for "if possible" semantics.
     * Subclasses implement this to provide framework-specific converters.
     *
     * @param outputClass The target output class (inner type of MaybeReturn)
     * @param interaction The LLM interaction context
     * @return An output converter for MaybeReturn<O>, or null to fall back to try-catch
     */
    internal open fun <O> createMaybeReturnOutputConverter(
        outputClass: Class<O>,
        interaction: LlmInteraction,
    ): OutputConverter<MaybeReturn<O>>? {
        // Default implementation returns null - subclasses should override
        return null
    }

    /**
     * Sanitize string output (e.g., remove thinking blocks).
     * Subclasses can override for custom sanitization.
     */
    protected open fun sanitizeStringOutput(text: String): String = text

    /**
     * Emit a call event for observability.
     * Subclasses can override to emit framework-specific events.
     */
    protected open fun emitCallEvent(
        llmRequestEvent: LlmRequestEvent<*>?,
        promptContributions: String,
        messages: List<Message>,
        schemaFormat: String?,
    ) {
        // Default: no-op. Subclasses can emit framework-specific events.
    }

    /**
     * Build prompt contributions from interaction and LLM.
     */
    protected fun buildPromptContributions(
        interaction: LlmInteraction,
        llm: LlmService<*>,
    ): String {
        return (interaction.promptContributors + llm.promptContributors)
            .joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }
    }

    /**
     * Build initial messages for the tool loop, including system prompt contributions and schema.
     * All system content is consolidated into a single system message at the beginning
     * to ensure proper message ordering for cross-model compatibility
     * (OpenAI best practice, required by DeepSeek, etc.).
     *
     * @see <a href="https://github.com/embabel/embabel-agent/issues/1295">GitHub Issue #1295</a>
     */
    protected fun buildInitialMessages(
        promptContributions: String,
        messages: List<Message>,
        schemaFormat: String? = null,
    ): List<Message> {
        // Extract system messages from input and separate non-system messages
        val systemContents = mutableListOf<String>()
        val nonSystemMessages = mutableListOf<Message>()

        // Add prompt contributions first (if any)
        if (promptContributions.isNotEmpty()) {
            systemContents.add(promptContributions)
        }

        // Partition input messages into system content and non-system messages
        for (message in messages) {
            if (message is SystemMessage) {
                systemContents.add(message.content)
            } else {
                nonSystemMessages.add(message)
            }
        }

        // Add schema format last in system content (if any)
        if (schemaFormat != null) {
            systemContents.add(schemaFormat)
        }

        // Build the final message list with consolidated system message first
        return buildList {
            if (systemContents.isNotEmpty()) {
                add(SystemMessage(systemContents.joinToString("\n\n")))
            }
            addAll(nonSystemMessages)
        }
    }

    /**
     * Build initial messages with MaybeReturn prompt for "if possible" semantics.
     * Adds the MaybeReturn prompt as a UserMessage after system message, before other messages.
     */
    protected fun buildInitialMessagesWithMaybeReturn(
        promptContributions: String,
        messages: List<Message>,
        maybeReturnPrompt: String,
        schemaFormat: String?,
    ): List<Message> {
        val baseMessages = buildInitialMessages(promptContributions, messages, schemaFormat)
        val firstMessage = baseMessages.firstOrNull()
        return if (firstMessage is SystemMessage) {
            // Keep system message first, insert MaybeReturn prompt, then remaining messages
            listOf(firstMessage, UserMessage(maybeReturnPrompt)) + baseMessages.drop(1)
        } else {
            listOf(UserMessage(maybeReturnPrompt)) + baseMessages
        }
    }

    /**
     * Record LLM usage for observability.
     */
    protected fun recordUsage(
        llm: LlmService<*>,
        usage: Usage,
        llmRequestEvent: LlmRequestEvent<*>?,
    ) {
        logger.debug("Usage is {}", usage)
        llmRequestEvent?.let {
            val llmi = LlmInvocation(
                llmMetadata = llm,
                usage = usage,
                agentName = it.agentProcess.agent.name,
                timestamp = it.timestamp,
                runningTime = Duration.between(it.timestamp, Instant.now()),
            )
            it.agentProcess.recordLlmInvocation(llmi)
        }
    }

    /**
     * Check if examples should be generated based on properties and interaction settings.
     */
    protected fun shouldGenerateExamples(llmCall: LlmCall): Boolean {
        if (promptsProperties.generateExamplesByDefault) {
            return llmCall.generateExamples != false
        }
        return llmCall.generateExamples == true
    }

    // ========== Private helper methods to reduce duplication ==========

    /**
     * Create a decorator for dynamically injected tools (e.g., from MatryoshkaTool).
     */
    private fun createInjectedToolDecorator(
        llmRequestEvent: LlmRequestEvent<*>?,
        interaction: LlmInteraction,
    ): ((Tool) -> Tool)? = llmRequestEvent?.let { event ->
        { tool: Tool ->
            toolDecorator.decorate(
                tool = tool,
                agentProcess = event.agentProcess,
                action = event.action,
                llmOptions = interaction.llm,
            )
        }
    }

    /**
     * Create the injection strategy based on interaction configuration.
     */
    private fun createInjectionStrategy(interaction: LlmInteraction): ToolInjectionStrategy =
        if (interaction.additionalInjectionStrategies.isNotEmpty()) {
            ChainedToolInjectionStrategy(
                listOf(ToolInjectionStrategy.DEFAULT) + interaction.additionalInjectionStrategies
            )
        } else {
            ToolInjectionStrategy.DEFAULT
        }

    /**
     * Publish ToolLoopStartEvent and return it for later completion tracking.
     */
    private fun <O> publishToolLoopStartEvent(
        llmRequestEvent: LlmRequestEvent<O>?,
        tools: List<Tool>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
    ): ToolLoopStartEvent? = llmRequestEvent?.let { event ->
        ToolLoopStartEvent(
            agentProcess = event.agentProcess,
            action = event.action,
            toolNames = tools.map { it.definition.name },
            maxIterations = interaction.maxToolIterations,
            interactionId = interaction.id.value,
            outputClass = outputClass,
        ).also { startEvent ->
            event.agentProcess.processContext.onProcessEvent(startEvent)
        }
    }

    /**
     * Handle tool loop completion: publish completed event, record usage, check for replan.
     * Throws ReplanRequestedException if replan was requested.
     */
    private fun <O> handleToolLoopCompletion(
        toolLoopStartEvent: ToolLoopStartEvent?,
        result: com.embabel.agent.spi.loop.ToolLoopResult<O>,
        llmRequestEvent: LlmRequestEvent<*>?,
        llm: LlmService<*>,
    ) {
        // Publish ToolLoopCompletedEvent after the tool loop
        toolLoopStartEvent?.let { startEvent ->
            llmRequestEvent!!.agentProcess.processContext.onProcessEvent(
                startEvent.completedEvent(
                    totalIterations = result.totalIterations,
                    replanRequested = result.replanRequested,
                )
            )
        }

        result.totalUsage?.let { usage ->
            recordUsage(llm, usage, llmRequestEvent)
        }

        // If replan was requested, re-throw the exception to propagate to action executor
        if (result.replanRequested) {
            throw ReplanRequestedException(
                reason = result.replanReason ?: "Tool requested replan",
                blackboardUpdater = result.blackboardUpdater,
            )
        }
    }

}
