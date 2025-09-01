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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.support.AbstractLlmOperations
import com.embabel.agent.event.LlmRequestEvent
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmCall
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.agent.spi.support.LlmOperationsPromptsProperties
import com.embabel.chat.Message
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.DatabindException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ResponseEntity
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.converter.BeanOutputConverter
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
    private val templateRenderer: TemplateRenderer,
    autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    private val dataBindingProperties: LlmDataBindingProperties = LlmDataBindingProperties(),
    private val llmOperationsPromptsProperties: LlmOperationsPromptsProperties = LlmOperationsPromptsProperties(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
) : AbstractLlmOperations(toolDecorator, modelProvider, autoLlmSelectionCriteriaResolver) {

    @Suppress("UNCHECKED_CAST")
    override fun <O> doTransform(
        messages: List<Message>,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {
        val llm = chooseLlm(interaction.llm)
        val chatClient = createChatClient(llm)
        val promptContributions =
            (interaction.promptContributors + llm.promptContributors).joinToString("\n") { it.contribution() }

        val springAiPrompt = Prompt(
            buildList {
                if (promptContributions.isNotEmpty()) {
                    add(SystemMessage(promptContributions))
                }
                addAll(messages.map { it.toSpringAiMessage() })
            }
        )
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
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                logger.warn(
                    "LLM {}: attempt {} timed out after {}ms",
                    interaction.id.value,
                    attempt,
                    timeoutMillis
                )
                throw RuntimeException(
                    "ChatClient call for interaction ${interaction.id.value} timed out after ${timeoutMillis}ms",
                    e
                )
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                logger.warn("LLM {}: attempt {} was interrupted", interaction.id.value, attempt)
                throw RuntimeException(
                    "ChatClient call for interaction ${interaction.id.value} was interrupted",
                    e
                )
            } catch (e: ExecutionException) {
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

            if (outputClass == String::class.java) {
                val chatResponse = callResponse.chatResponse()
                chatResponse?.let { recordUsage(llm, it, llmRequestEvent) }
                chatResponse!!.result.output.text as O
            } else {
                val re = callResponse.responseEntity<O>(
                    ExceptionWrappingConverter(
                        expectedType = outputClass,
                        delegate = WithExampleConverter(
                            delegate = SuppressThinkingConverter(
                                BeanOutputConverter(outputClass, objectMapper)
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
                usage = chatResponse.metadata.usage,
                agentName = it.agentProcess.agent.name,
                timestamp = it.timestamp,
                runningTime = Duration.between(it.timestamp, Instant.now()),
            )
            it.agentProcess.recordLlmInvocation(llmi)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <O> doTransformIfPossible(
        prompt: String,
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
        val springAiPrompt = Prompt(
            buildList {
                if (promptContributions.isNotEmpty()) {
                    add(SystemMessage(promptContributions))
                }
                add(UserMessage("Instruction: <$prompt>\n\n$maybeReturnPromptContribution"))
            }
        )
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
                                    "LLM {}: attempt {} timed out after {}ms",
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
                    .get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn(
                    "LLM {}: attempt {} was interrupted",
                    interaction.id.value,
                    attempt
                )
                throw RuntimeException(
                    "ChatClient call for interaction ${interaction.id.value} was interrupted",
                    e
                )
            }

            val responseEntity: ResponseEntity<ChatResponse, MaybeReturn<*>> = callResponse
                .responseEntity<MaybeReturn<*>>(
                    ExceptionWrappingConverter(
                        expectedType = MaybeReturn::class.java,
                        delegate = WithExampleConverter(
                            delegate = SuppressThinkingConverter(
                                BeanOutputConverter(typeReference, objectMapper)
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
        return object : org.springframework.core.ParameterizedTypeReference<T>() {
            override fun getType() = type
        }
    }


    private fun createChatClient(
        llm: Llm,
    ): ChatClient {
        return ChatClient
            .builder(llm.model)
            .build()
    }

    private fun shouldGenerateExamples(llmCall: LlmCall): Boolean {
        if (llmOperationsPromptsProperties.generateExamplesByDefault) {
            return llmCall.generateExamples != false
        }
        return llmCall.generateExamples == true
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
