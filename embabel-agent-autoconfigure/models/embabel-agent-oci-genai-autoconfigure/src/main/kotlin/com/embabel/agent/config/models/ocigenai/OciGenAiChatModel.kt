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
package com.embabel.agent.config.models.ocigenai

import com.fasterxml.jackson.databind.ObjectMapper
import com.oracle.bmc.generativeaiinference.GenerativeAiInference
import com.oracle.bmc.generativeaiinference.model.BaseChatResponse
import com.oracle.bmc.generativeaiinference.model.BaseChatRequest
import com.oracle.bmc.generativeaiinference.model.ChatDetails
import com.oracle.bmc.generativeaiinference.model.ChatResult
import com.oracle.bmc.generativeaiinference.model.CohereAssistantMessageV2
import com.oracle.bmc.generativeaiinference.model.CohereChatBotMessage
import com.oracle.bmc.generativeaiinference.model.CohereChatRequest
import com.oracle.bmc.generativeaiinference.model.CohereChatRequestV2
import com.oracle.bmc.generativeaiinference.model.CohereChatResponse
import com.oracle.bmc.generativeaiinference.model.CohereChatResponseV2
import com.oracle.bmc.generativeaiinference.model.CohereMessage
import com.oracle.bmc.generativeaiinference.model.CohereMessageV2
import com.oracle.bmc.generativeaiinference.model.CohereSystemMessageV2
import com.oracle.bmc.generativeaiinference.model.CohereTextContentV2
import com.oracle.bmc.generativeaiinference.model.CohereToolMessageV2
import com.oracle.bmc.generativeaiinference.model.CohereToolV2
import com.oracle.bmc.generativeaiinference.model.CohereUserMessage
import com.oracle.bmc.generativeaiinference.model.CohereUserMessageV2
import com.oracle.bmc.generativeaiinference.model.DedicatedServingMode
import com.oracle.bmc.generativeaiinference.model.FunctionCall
import com.oracle.bmc.generativeaiinference.model.FunctionDefinition
import com.oracle.bmc.generativeaiinference.model.GenericChatRequest
import com.oracle.bmc.generativeaiinference.model.GenericChatResponse
import com.oracle.bmc.generativeaiinference.model.OnDemandServingMode
import com.oracle.bmc.generativeaiinference.model.ServingMode
import com.oracle.bmc.generativeaiinference.model.TextContent
import com.oracle.bmc.generativeaiinference.model.ToolChoiceAuto
import com.oracle.bmc.generativeaiinference.model.Usage
import com.oracle.bmc.generativeaiinference.requests.ChatRequest
import com.oracle.bmc.generativeaiinference.responses.ChatResponse as OciChatResponse
import com.oracle.bmc.generativeaiinference.model.CohereToolCallV2 as OciCohereToolCallV2
import com.oracle.bmc.generativeaiinference.model.Function as CohereFunction
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.retry.support.RetryTemplate
import org.springframework.ai.chat.messages.AssistantMessage as SpringAssistantMessage
import org.springframework.ai.chat.messages.Message as SpringMessage
import com.oracle.bmc.generativeaiinference.model.AssistantMessage as OciAssistantMessage
import com.oracle.bmc.generativeaiinference.model.Message as OciMessage
import com.oracle.bmc.generativeaiinference.model.SystemMessage as OciSystemMessage
import com.oracle.bmc.generativeaiinference.model.ToolMessage as OciToolMessage
import com.oracle.bmc.generativeaiinference.model.UserMessage as OciUserMessage

/**
 * Spring AI [ChatModel] backed by OCI Generative AI Inference.
 *
 * OCI streaming uses SSE response handling that is separate from the synchronous
 * chat response path, so this model currently sends non-streaming requests.
 */
class OciGenAiChatModel(
    private val client: GenerativeAiInference,
    private val defaultOptions: OciGenAiChatOptions,
    private val retryTemplate: RetryTemplate,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ChatModel {
    private val logger = LoggerFactory.getLogger(OciGenAiChatModel::class.java)

    override fun call(prompt: Prompt): ChatResponse {
        val options = defaultOptions.merge(prompt.options)
        val request = ChatRequest.builder()
            .chatDetails(
                ChatDetails.builder()
                    .compartmentId(requireCompartmentId(options))
                    .servingMode(servingMode(options))
                    .chatRequest(chatRequest(prompt, options))
                    .build()
            )
            .build()

        val response = retryTemplate.execute<OciChatResponse, RuntimeException> {
            client.chat(request)
        }
        return toSpringChatResponse(response.chatResult, options)
    }

    override fun getDefaultOptions(): ChatOptions = defaultOptions.copy()

    internal fun chatRequest(prompt: Prompt, options: OciGenAiChatOptions): BaseChatRequest =
        when (options.apiFormat) {
            OciGenAiApiFormat.GENERIC -> genericChatRequest(prompt, options)
            OciGenAiApiFormat.COHERE_V2 -> cohereChatRequestV2(prompt, options)
            OciGenAiApiFormat.COHERE -> cohereChatRequest(prompt, options)
        }

    private fun genericChatRequest(prompt: Prompt, options: OciGenAiChatOptions): GenericChatRequest {
        val tools = options.toolCallbacks.map { toolCallback ->
            val definition = toolCallback.toolDefinition
            FunctionDefinition.builder()
                .name(definition.name())
                .description(definition.description())
                .parameters(parseToolSchema(definition.inputSchema()))
                .build()
        }

        val builder = GenericChatRequest.builder()
            .messages(prompt.instructions.flatMap(::toGenericMessages))
            .isStream(false)
            .maxTokens(options.maxTokens)
            .temperature(options.temperature)
            .topP(options.topP)
            .topK(options.topK)
            .frequencyPenalty(options.frequencyPenalty)
            .presencePenalty(options.presencePenalty)
            .stop(options.stopSequences)

        if (tools.isNotEmpty()) {
            builder.tools(tools)
                .toolChoice(ToolChoiceAuto.builder().build())
                .isParallelToolCalls(false)
        }
        return builder.build()
    }

    private fun cohereChatRequestV2(prompt: Prompt, options: OciGenAiChatOptions): CohereChatRequestV2 {
        val tools = options.toolCallbacks.map { toolCallback ->
            val definition = toolCallback.toolDefinition
            CohereToolV2.builder()
                .type(CohereToolV2.Type.Function)
                .function(
                    CohereFunction.builder()
                        .name(definition.name())
                        .description(definition.description())
                        .parameters(parseToolSchema(definition.inputSchema()))
                        .build()
                )
                .build()
        }

        val builder = CohereChatRequestV2.builder()
            .messages(prompt.instructions.flatMap(::toCohereV2Messages))
            .isStream(false)
            .maxTokens(options.maxTokens)
            .temperature(options.temperature)
            .topP(options.topP)
            .topK(options.topK)
            .frequencyPenalty(options.frequencyPenalty)
            .presencePenalty(options.presencePenalty)
            .stopSequences(options.stopSequences)

        if (tools.isNotEmpty()) {
            builder.tools(tools)
        }
        return builder.build()
    }

    private fun cohereChatRequest(prompt: Prompt, options: OciGenAiChatOptions): CohereChatRequest {
        val messages = prompt.instructions
        val lastUserIndex = messages.indexOfLast { it.messageType == MessageType.USER }
        val lastRequestMessageIndex = if (lastUserIndex >= 0) lastUserIndex else messages.lastIndex
        val lastUserMessage = messages.getOrNull(lastRequestMessageIndex)?.text ?: ""
        val preamble = messages
            .filter { it.messageType == MessageType.SYSTEM }
            .joinToString("\n") { it.text }
            .ifBlank { null }
        val history = if (lastRequestMessageIndex > 0) {
            messages.subList(0, lastRequestMessageIndex)
                .filter { it.messageType != MessageType.SYSTEM }
                .mapNotNull(::toCohereHistoryMessage)
        } else {
            emptyList()
        }

        return CohereChatRequest.builder()
            .message(lastUserMessage)
            .chatHistory(history)
            .preambleOverride(preamble)
            .isStream(false)
            .maxTokens(options.maxTokens)
            .temperature(options.temperature)
            .topP(options.topP)
            .topK(options.topK)
            .frequencyPenalty(options.frequencyPenalty)
            .presencePenalty(options.presencePenalty)
            .stopSequences(options.stopSequences)
            .build()
    }

    internal fun toGenericMessages(message: SpringMessage): List<OciMessage> =
        when (message.messageType) {
            MessageType.SYSTEM -> listOf(OciSystemMessage.builder().content(textContent(message.text)).build())
            MessageType.USER -> listOf(OciUserMessage.builder().content(textContent(message.text)).build())
            MessageType.ASSISTANT -> listOf(toGenericAssistantMessage(message))
            MessageType.TOOL -> toGenericToolMessages(message)
            else -> listOf(OciUserMessage.builder().content(textContent(message.text)).build())
        }

    private fun toGenericAssistantMessage(message: SpringMessage): OciAssistantMessage {
        val assistant = message as? SpringAssistantMessage
        val toolCalls = assistant?.toolCalls
            ?.map {
                FunctionCall.builder()
                    .id(it.id())
                    .name(it.name())
                    .arguments(it.arguments())
                    .build()
            }
            ?: emptyList()
        return OciAssistantMessage.builder()
            .content(textContent(message.text ?: ""))
            .toolCalls(toolCalls)
            .build()
    }

    private fun toGenericToolMessages(message: SpringMessage): List<OciMessage> {
        val toolResponseMessage = message as? ToolResponseMessage
            ?: return listOf(OciUserMessage.builder().content(textContent(message.text ?: "")).build())
        return toolResponseMessage.responses.map { response ->
            OciToolMessage.builder()
                .toolCallId(response.id())
                .content(textContent(response.responseData()))
                .build()
        }
    }

    internal fun toCohereV2Messages(message: SpringMessage): List<CohereMessageV2> =
        when (message.messageType) {
            MessageType.SYSTEM -> listOf(
                CohereSystemMessageV2.builder().content(cohereV2TextContent(message.text)).build()
            )

            MessageType.USER -> listOf(
                CohereUserMessageV2.builder().content(cohereV2TextContent(message.text)).build()
            )

            MessageType.ASSISTANT -> listOf(toCohereV2AssistantMessage(message))
            MessageType.TOOL -> toCohereV2ToolMessages(message)
            else -> listOf(CohereUserMessageV2.builder().content(cohereV2TextContent(message.text)).build())
        }

    private fun toCohereV2AssistantMessage(message: SpringMessage): CohereAssistantMessageV2 {
        val assistant = message as? SpringAssistantMessage
        val toolCalls = assistant?.toolCalls
            ?.map {
                OciCohereToolCallV2.builder()
                    .id(it.id())
                    .type(OciCohereToolCallV2.Type.Function)
                    .function(parseToolArguments(it.arguments()))
                    .build()
            }
            ?: emptyList()
        return CohereAssistantMessageV2.builder()
            .content(cohereV2TextContent(message.text ?: ""))
            .toolCalls(toolCalls)
            .build()
    }

    private fun toCohereV2ToolMessages(message: SpringMessage): List<CohereMessageV2> {
        val toolResponseMessage = message as? ToolResponseMessage
            ?: return listOf(CohereUserMessageV2.builder().content(cohereV2TextContent(message.text ?: "")).build())
        return toolResponseMessage.responses.map { response ->
            CohereToolMessageV2.builder()
                .toolCallId(response.id())
                .content(cohereV2TextContent(response.responseData()))
                .build()
        }
    }

    private fun toCohereHistoryMessage(message: SpringMessage): CohereMessage? =
        when (message.messageType) {
            MessageType.USER -> CohereUserMessage.builder().message(message.text).build()
            MessageType.ASSISTANT -> CohereChatBotMessage.builder().message(message.text ?: "").build()
            else -> null
        }

    private fun textContent(text: String): List<TextContent> =
        listOf(TextContent.builder().text(text).build())

    private fun cohereV2TextContent(text: String): List<CohereTextContentV2> =
        listOf(CohereTextContentV2.builder().text(text).build())

    private fun parseToolSchema(inputSchema: String): Any =
        runCatching { objectMapper.readValue(inputSchema, Any::class.java) }
            .getOrElse { e ->
                logger.warn("Failed to parse OCI GenAI tool schema; using empty parameters: {}", e.message)
                emptyMap<String, Any>()
            }

    private fun parseToolArguments(arguments: String): Any =
        runCatching { objectMapper.readValue(arguments, Any::class.java) }
            .getOrElse { e ->
                logger.warn("Failed to parse OCI GenAI tool arguments; using raw arguments: {}", e.message)
                arguments
            }

    private fun servingMode(options: OciGenAiChatOptions): ServingMode =
        when (options.servingMode) {
            OciGenAiServingMode.ON_DEMAND -> OnDemandServingMode.builder()
                .modelId(requireNotNull(options.getModel()) { "OCI GenAI model is required for on-demand serving" })
                .build()

            OciGenAiServingMode.DEDICATED -> DedicatedServingMode.builder()
                .endpointId(requireNotNull(options.endpointId) { "OCI GenAI endpointId is required for dedicated serving" })
                .build()
        }

    private fun requireCompartmentId(options: OciGenAiChatOptions): String =
        requireNotNull(options.compartmentId) {
            "OCI GenAI compartmentId is required: set embabel.agent.platform.models.ocigenai.compartment-id"
        }

    private fun toSpringChatResponse(chatResult: ChatResult, options: OciGenAiChatOptions): ChatResponse {
        val (generation, usage) = generationAndUsage(chatResult.chatResponse)
        val metadata = ChatResponseMetadata.builder()
            .model(chatResult.modelId ?: options.getModel())
            .usage(
                usage?.let {
                    DefaultUsage(it.promptTokens, it.completionTokens, it.totalTokens, it)
                }
            )
            .build()
        return ChatResponse(listOf(generation), metadata)
    }

    private fun generationAndUsage(response: BaseChatResponse?): GenerationAndUsage =
        when (response) {
            is GenericChatResponse -> GenerationAndUsage(toGenericGeneration(response), response.usage)
            is CohereChatResponseV2 -> GenerationAndUsage(toCohereV2Generation(response), response.usage)
            is CohereChatResponse -> GenerationAndUsage(toCohereGeneration(response), response.usage)
            else -> GenerationAndUsage(Generation(SpringAssistantMessage("")), null)
        }

    private fun toGenericGeneration(response: GenericChatResponse): Generation {
        val choice = response.choices.firstOrNull()
        val message = choice?.message
        val text = message?.content.orEmpty()
            .filterIsInstance<TextContent>()
            .joinToString("") { it.text ?: "" }
        val toolCalls = (message as? OciAssistantMessage)?.toolCalls.orEmpty()
            .filterIsInstance<FunctionCall>()
            .map { SpringAssistantMessage.ToolCall(it.id, "function", it.name, it.arguments) }
        val assistant = SpringAssistantMessage.builder()
            .content(text)
            .toolCalls(toolCalls)
            .build()
        val generationMetadata = ChatGenerationMetadata.builder()
            .finishReason(choice?.finishReason)
            .build()
        return Generation(assistant, generationMetadata)
    }

    private fun toCohereV2Generation(response: CohereChatResponseV2): Generation {
        val message = response.message
        val text = message?.content.orEmpty()
            .filterIsInstance<CohereTextContentV2>()
            .joinToString("") { it.text ?: "" }
        val toolCalls = message?.toolCalls.orEmpty()
            .map {
                SpringAssistantMessage.ToolCall(
                    it.id,
                    "function",
                    cohereV2ToolName(it.function),
                    cohereV2ToolArguments(it.function),
                )
            }
        val assistant = SpringAssistantMessage.builder()
            .content(text)
            .toolCalls(toolCalls)
            .build()
        val generationMetadata = ChatGenerationMetadata.builder()
            .finishReason(response.finishReason?.value)
            .build()
        return Generation(assistant, generationMetadata)
    }

    private fun cohereV2ToolName(function: Any?): String =
        when (function) {
            is CohereFunction -> function.name
            is Map<*, *> -> function["name"]?.toString() ?: ""
            else -> ""
        }

    private fun cohereV2ToolArguments(function: Any?): String =
        when (function) {
            is CohereFunction -> objectMapper.writeValueAsString(function.parameters ?: emptyMap<String, Any>())
            is Map<*, *> -> objectMapper.writeValueAsString(function["parameters"] ?: function["arguments"] ?: emptyMap<String, Any>())
            else -> "{}"
        }

    private fun toCohereGeneration(response: CohereChatResponse): Generation {
        val generationMetadata = ChatGenerationMetadata.builder()
            .finishReason(response.finishReason?.value)
            .build()
        return Generation(SpringAssistantMessage(response.text ?: ""), generationMetadata)
    }

    private data class GenerationAndUsage(
        val generation: Generation,
        val usage: Usage?,
    )
}
