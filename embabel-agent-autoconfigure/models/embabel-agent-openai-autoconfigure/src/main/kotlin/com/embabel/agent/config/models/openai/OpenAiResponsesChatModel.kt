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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.models.OpenAiModels
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.ResponsesModel
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseStatus
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.Tool
import io.micrometer.observation.ObservationRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.MediaContent
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.retry.NonTransientAiException
import reactor.core.publisher.Flux
import java.util.function.Supplier
import com.openai.models.responses.Response as OpenAiResponse

/** Options specific to models served through OpenAI's Responses API. */
object OpenAiResponsesOptions {
    /** Key used in [OpenAiChatOptions.extraBody] to continue a stored conversation. */
    const val PREVIOUS_RESPONSE_ID = "previous_response_id"
}

/**
 * Spring AI [ChatModel] backed by the OpenAI Responses API, which serves the `*-pro` models.
 *
 * Spring AI has no client for that endpoint — native support is milestoned for 2.1
 * (spring-ai#2962) — so this adapts the official OpenAI SDK, as
 * [com.embabel.agent.config.models.ocigenai.OciGenAiChatModel] adapts the OCI SDK.
 *
 * Only the single request/response exchange is mapped. Embabel drives its own tool loop, so the
 * stateful half of the Responses API — background mode, server-side conversation state, remote
 * MCP — is deliberately unused, and requests are non-streaming.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 * @see <a href="https://github.com/spring-projects/spring-ai/issues/2962">spring-ai#2962</a>
 */
class OpenAiResponsesChatModel(
    private val client: OpenAIClient,
    private val defaultOptions: OpenAiChatOptions,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ChatModel {

    /**
     * Observed under the same convention as Spring AI's own chat models. Embabel's `embabel.llm`
     * and `embabel.tool_loop` spans are deliberately thin because they expect the generation
     * content — prompt, completion, token counts — on this nested observation; without it, calls
     * to these models would drop out of every trace.
     */
    override fun call(prompt: Prompt): ChatResponse {
        val context = ChatModelObservationContext.builder()
            .prompt(prompt)
            .provider(OpenAiModels.PROVIDER)
            .build()

        return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
            .observation(null, OBSERVATION_CONVENTION, { context }, observationRegistry)
            .observe(
                Supplier {
                    toChatResponse(client.responses().create(createParams(prompt)))
                        .also { context.response = it }
                }
            )!!
    }

    /** Defensive copy: Spring AI hands these to callers that may mutate them. */
    override fun getDefaultOptions(): ChatOptions = defaultOptions.mutate().build()

    /** Throwing is how `SpringAiLlmService.StreamingCapabilityVerifier` learns this model has no stream. */
    override fun stream(prompt: Prompt): Flux<ChatResponse> =
        throw UnsupportedOperationException(
            "Streaming is not supported for OpenAI models served over the Responses API"
        )

    private fun createParams(prompt: Prompt): ResponseCreateParams {
        val options = prompt.options
        val builder = ResponseCreateParams.builder()
            .model(modelOf(options))
            .inputOfResponse(
                prompt.instructions
                    .filterNot { it.messageType == MessageType.SYSTEM }
                    .flatMap(::toInputItems)
            )
        instructionsOf(prompt)?.let(builder::instructions)
        previousResponseIdOf(options)?.let(builder::previousResponseId)
        maxOutputTokensOf(options)?.let { builder.maxOutputTokens(it.toLong()) }
        toolsOf(options).takeIf { it.isNotEmpty() }?.let(builder::tools)
        textConfigOf(options)?.let(builder::text)
        // No sampling parameters. Gpt5ChatOptionsConverter stopped setting them, and the Responses
        // API has no presence/frequency penalty to carry them anyway; options built elsewhere are
        // dropped here rather than refused by the endpoint.
        return builder.build()
    }

    private fun previousResponseIdOf(options: ChatOptions?): String? =
        ((options as? OpenAiChatOptions)?.extraBody?.get(OpenAiResponsesOptions.PREVIOUS_RESPONSE_ID)
            ?: defaultOptions.extraBody?.get(OpenAiResponsesOptions.PREVIOUS_RESPONSE_ID))
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    /**
     * Since #1857 the provider converters stamp the configured model themselves, so a request built
     * through one always carries it. The default covers options built without a converter.
     */
    private fun modelOf(options: ChatOptions?): String =
        options?.model
            ?: defaultOptions.model
            ?: error("No model configured for a Responses API call")

    /**
     * The models reached over this transport are served by
     * [com.embabel.agent.openai.Gpt5ChatOptionsConverter], which carries the caller's limit on
     * `maxCompletionTokens` because the GPT-5 family rejects `max_tokens`. Both fields are read so
     * that options built elsewhere still get their limit through.
     *
     * `max_output_tokens` covers reasoning tokens as well as visible output, so a limit sized for
     * the answer alone comes back truncated — see the `length` finish reason in [finishReasonOf].
     */
    private fun maxOutputTokensOf(options: ChatOptions?): Int? {
        val openAiOptions = options as? OpenAiChatOptions
        return openAiOptions?.maxCompletionTokens
            ?: openAiOptions?.maxTokens
            ?: defaultOptions.maxCompletionTokens
            ?: defaultOptions.maxTokens
    }

    /** The Responses API has no system role; system prompts are carried as `instructions`. */
    private fun instructionsOf(prompt: Prompt): String? =
        prompt.instructions
            .filter { it.messageType == MessageType.SYSTEM }
            .mapNotNull { it.text }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    private fun toInputItems(message: Message): List<ResponseInputItem> = when (message) {
        is ToolResponseMessage -> message.responses.map(::toFunctionCallOutput)
        is AssistantMessage -> buildList {
            message.text?.takeIf { it.isNotBlank() }?.let {
                add(toEasyInputMessage(EasyInputMessage.Role.ASSISTANT, it))
            }
            message.toolCalls.orEmpty().forEach { add(toFunctionCall(it)) }
        }

        else -> listOf(toEasyInputMessage(EasyInputMessage.Role.USER, textOf(message)))
    }

    /**
     * Only text is mapped. Refused rather than dropped: a prompt stripped of its image still gets a
     * confident answer, about something the model never saw.
     */
    private fun textOf(message: Message): String {
        if ((message as? MediaContent)?.media.orEmpty().isNotEmpty()) {
            throw UnsupportedOperationException(
                "Media is not supported for OpenAI models served over the Responses API"
            )
        }
        return message.text.orEmpty()
    }

    private fun toEasyInputMessage(role: EasyInputMessage.Role, content: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder().role(role).content(content).build()
        )

    /**
     * Embabel replays the assistant's tool calls and their results on the next turn; the Responses
     * API pairs the two by `call_id`, so the id has to survive both directions unchanged.
     */
    private fun toFunctionCall(toolCall: AssistantMessage.ToolCall): ResponseInputItem =
        ResponseInputItem.ofFunctionCall(
            ResponseFunctionToolCall.builder()
                .callId(toolCall.id)
                .name(toolCall.name)
                .arguments(toolCall.arguments)
                .build()
        )

    private fun toFunctionCallOutput(response: ToolResponseMessage.ToolResponse): ResponseInputItem =
        ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(response.id())
                .output(response.responseData())
                .build()
        )

    private fun toolsOf(options: ChatOptions?): List<Tool> =
        (options as? ToolCallingChatOptions)?.toolCallbacks.orEmpty().map { callback ->
            val definition = callback.toolDefinition
            Tool.ofFunction(
                FunctionTool.builder()
                    .name(definition.name())
                    .description(definition.description())
                    .parameters(toParameters(definition.inputSchema(), definition.name()))
                    .strict(false)
                    .build()
            )
        }

    /**
     * Native structured output reaches us as a Chat Completions `response_format`, set by
     * [OpenAiNativeStructuredOutputConfigurer]. The Responses API carries the same schema under
     * `text.format`, and — unlike Chat Completions — requires it to be named, so a name is derived
     * from the schema's own title where it has one.
     */
    private fun textConfigOf(options: ChatOptions?): ResponseTextConfig? {
        val responseFormat = (options as? OpenAiChatOptions)?.responseFormat ?: return null
        if (responseFormat.type != OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA) {
            return null
        }
        val schema = responseFormat.jsonSchema?.let { readSchema(it, "response format schema") }
            ?: return null
        return ResponseTextConfig.builder()
            .format(
                ResponseFormatTextJsonSchemaConfig.builder()
                    .name(schemaNameOf(schema))
                    .schema(toSchema(schema))
                    // Strict mode rejects schemas Embabel routinely produces (open objects,
                    // optional fields), and the catalog already declares strict: false.
                    .strict(false)
                    .build()
            )
            .build()
    }

    /**
     * Fails loudly: a dropped response format leaves the model answering in free text, and dropped
     * tool parameters tell it the tool takes none — both fail later, pointing at the wrong thing.
     */
    private fun readSchema(json: String, what: String): Map<String, Any?> =
        try {
            objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot read $what as JSON: ${e.message}", e)
        }

    /**
     * The Responses API requires the schema to be named and accepts only `[a-zA-Z0-9_-]` in that
     * name, so every other character is folded to an underscore, one for one:
     *
     * - `Answer` stays `Answer`
     * - `com.example.Answer` becomes `com_example_Answer`
     * - `List<Answer>` becomes `List_Answer_`
     *
     * The name comes from the schema's own `title` because that is all this adapter is given:
     * [OpenAiNativeStructuredOutputConfigurer] writes the schema into `responseFormat`, but
     * `StructuredOutputRequest.name` does not survive into [OpenAiChatOptions].
     *
     * An absent or empty title falls back to [DEFAULT_SCHEMA_NAME]. The name is a label OpenAI
     * echoes back, not something the model reasons over, so it is never worth failing a call for.
     */
    private fun schemaNameOf(schema: Map<String, Any?>): String =
        (schema["title"] as? String)
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SCHEMA_NAME

    private fun toSchema(schema: Map<String, Any?>): ResponseFormatTextJsonSchemaConfig.Schema {
        val builder = ResponseFormatTextJsonSchemaConfig.Schema.builder()
        schema.forEach { (key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)) }
        return builder.build()
    }

    private fun toParameters(inputSchema: String, toolName: String): FunctionTool.Parameters {
        val builder = FunctionTool.Parameters.builder()
        readSchema(inputSchema, "input schema of tool '$toolName'")
            .forEach { (key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)) }
        return builder.build()
    }

    private fun toChatResponse(response: OpenAiResponse): ChatResponse {
        raiseIfFailed(response)

        val content = response.output()
            .mapNotNull { it.message().orElse(null) }
            .flatMap { it.content() }
        raiseIfRefused(content)

        val text = content.mapNotNull { it.outputText().orElse(null)?.text() }.joinToString("\n")
        val toolCalls = response.output()
            .filter { it.isFunctionCall() }
            .map { it.asFunctionCall() }
            .map { AssistantMessage.ToolCall(it.callId(), FUNCTION_CALL_TYPE, it.name(), it.arguments()) }

        val usage = response.usage().orElse(null)?.let {
            DefaultUsage(it.inputTokens().toInt(), it.outputTokens().toInt(), it.totalTokens().toInt(), it)
        }
        val metadata = ChatResponseMetadata.builder()
            .id(response.id())
            .model(modelNameOf(response.model()))
            .usage(usage)
            .build()

        // Always one generation, even when the model returned nothing usable: a reasoning model can
        // answer with reasoning items alone, and SpringAiLlmMessageSender dereferences the first
        // result without a null check.
        val assistantMessage = AssistantMessage.builder()
            .content(text)
            .toolCalls(toolCalls)
            .build()
        val generationMetadata = ChatGenerationMetadata.builder()
            .finishReason(finishReasonOf(response, toolCalls.isNotEmpty()))
            .build()
        return ChatResponse(listOf(Generation(assistantMessage, generationMetadata)), metadata)
    }

    /**
     * A call that produced no answer still arrives inside a `200`, so nothing throws on its own and
     * left alone it reads as a model with nothing to say. [NonTransientAiException] is what
     * `SpringAiRetryPolicy` reads to decide against a retry.
     */
    private fun raiseIfFailed(response: OpenAiResponse) {
        val status = response.status().orElse(null) ?: return
        if (status !in ANSWERLESS_STATUSES) return
        val error = response.error().orElse(null)
        throw NonTransientAiException(
            "OpenAI Responses API call ${status.asString()}: ${error?.message() ?: "no reason given"}"
        )
    }

    /** A refusal is `completed` with no output text — the same silence, from a different cause. */
    private fun raiseIfRefused(content: List<ResponseOutputMessage.Content>) {
        val refusal = content.firstNotNullOfOrNull { it.refusal().orElse(null)?.refusal() } ?: return
        throw NonTransientAiException("OpenAI Responses API call refused: $refusal")
    }

    /**
     * In the Chat Completions vocabulary the rest of Spring AI speaks. Earns its keep on
     * `incomplete`, routine here because `max_output_tokens` is spent on reasoning too: the partial
     * answer is worth returning, but silently it reads as a complete one.
     */
    private fun finishReasonOf(response: OpenAiResponse, hasToolCalls: Boolean): String =
        when (response.status().orElse(null)) {
            null, ResponseStatus.COMPLETED -> if (hasToolCalls) "tool_calls" else "stop"

            ResponseStatus.INCOMPLETE ->
                when (response.incompleteDetails().orElse(null)?.reason()?.orElse(null)) {
                    OpenAiResponse.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS -> "length"
                    OpenAiResponse.IncompleteDetails.Reason.CONTENT_FILTER -> "content_filter"
                    else -> "incomplete"
                }.also { logger.warn("Incomplete response from {}: {}", modelNameOf(response.model()), it) }

            else -> response.status().get().asString()
        }

    /**
     * [ResponsesModel] is a union and each accessor throws unless it holds that variant: OpenAI's
     * own model ids deserialize to [ResponsesModel.chat], not to a plain string.
     */
    private fun modelNameOf(model: ResponsesModel): String = when {
        model.isString() -> model.asString()
        model.isChat() -> model.chat().get().asString()
        model.isOnly() -> model.only().get().asString()
        else -> model.toString()
    }

    private companion object {
        /** The type Spring AI expects on an [AssistantMessage.ToolCall]. */
        const val FUNCTION_CALL_TYPE = "function"

        /** Used when the schema carries no usable title. */
        const val DEFAULT_SCHEMA_NAME = "response"

        /** Terminal statuses that carry no answer at all. */
        val ANSWERLESS_STATUSES = setOf(ResponseStatus.FAILED, ResponseStatus.CANCELLED)

        val OBSERVATION_CONVENTION = DefaultChatModelObservationConvention()

        val logger: Logger = LoggerFactory.getLogger(OpenAiResponsesChatModel::class.java)
    }
}
