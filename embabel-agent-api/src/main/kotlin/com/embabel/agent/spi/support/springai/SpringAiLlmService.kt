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

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.support.springai.streaming.SpringAiLlmMessageStreamer
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.model.*
import com.embabel.common.ai.prompt.KnowledgeCutoffDate
import com.embabel.common.ai.prompt.PromptContributor
import tools.jackson.databind.annotation.JsonSerialize
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.time.Duration
import java.time.LocalDate

/**
 * Helper object for verifying streaming capability of ChatModel instances.
 *
 * Spring AI's ChatModel interface extends StreamingChatModel, but not all implementations
 * provide meaningful streaming support. Some may throw UnsupportedOperationException,
 * return empty Flux, or provide stub implementations.
 *
 * This helper performs lightweight behavioral testing to determine if a model
 * actually supports streaming operations.
 */
private object StreamingCapabilityVerifier {
    private const val TEST_PROMPT_MESSAGE = "Say 'test' to confirm streaming works"
    private const val STREAMING_TEST_TIMEOUT_MS = 100L

    fun supportsStreaming(chatModel: ChatModel): Boolean {
        return try {
            val testRequest = Prompt(listOf(UserMessage(TEST_PROMPT_MESSAGE)))
            val stream = chatModel.stream(testRequest)
            canConsumeStream(stream)
            true
        } catch (e: UnsupportedOperationException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun canConsumeStream(stream: Flux<ChatResponse>): Boolean {
        return try {
            stream.hasElements()
                .timeout(Duration.ofMillis(STREAMING_TEST_TIMEOUT_MS))
                .block()
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Spring AI implementation that provides decoupled LLM operations.
 *
 * Wraps a Spring AI [ChatModel] and provides the ability to create
 * [LlmMessageSender] instances for making LLM calls without tight coupling
 * to Spring AI throughout the codebase.
 *
 * This class is the recommended replacement for the deprecated
 * [com.embabel.common.ai.model.Llm] class.
 *
 * @param name Name of the LLM
 * @param provider Name of the provider (e.g., "OpenAI", "Anthropic")
 * @param chatModel The Spring AI ChatModel to use for LLM calls
 * @param optionsConverter Function to convert [LlmOptions] to Spring AI ChatOptions
 * @param knowledgeCutoffDate Model's knowledge cutoff date, if known
 * @param promptContributors List of prompt contributors for this model.
 *        Knowledge cutoff is automatically included if knowledgeCutoffDate is set.
 * @param pricingModel Pricing model for this LLM, if known
 * @param thinkingSupported Whether this model supports Embabel thinking operations,
 *        including generic thinking extraction or provider-native reasoning exposed
 *        through Embabel's thinking mode.
 * @param toolResponseContentAdapter Adapts tool response content for provider-specific
 *        format requirements. Defaults to [ToolResponseContentAdapter.PASSTHROUGH].
 *        Google GenAI requires JSON; OpenAI/Anthropic accept plain text.
 * @param nativeStructuredOutputConfigurer Spring AI-specific translator for native structured-output
 *        request metadata. Defaults to no-op so unsupported providers keep prompt-schema fallback.
 */
@JsonSerialize(`as` = LlmMetadata::class)
data class SpringAiLlmService @JvmOverloads constructor(
    override val name: String,
    override val provider: String,
    @get:JvmName("getChatModel")
    val chatModel: ChatModel,
    val optionsConverter: OptionsConverter<*> = DefaultOptionsConverter,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val promptContributors: List<PromptContributor> =
        buildList { knowledgeCutoffDate?.let { add(KnowledgeCutoffDate(it)) } },
    override val pricingModel: PricingModel? = null,
    val thinkingSupported: Boolean = false,
    val toolResponseContentAdapter: ToolResponseContentAdapter = ToolResponseContentAdapter.PASSTHROUGH,
    val nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer =
        SpringAiNativeStructuredOutputConfigurer.NOOP,
    val nativeSupport: NativeSupport? = null,
) : LlmService<SpringAiLlmService>, AiModel<ChatModel> {

    /**
     * The underlying Spring AI ChatModel.
     * Exposed via [AiModel] interface for backward compatibility.
     */
    override val model: ChatModel get() = chatModel

    /**
     * Binds the model configured on the underlying [ChatModel] onto per-request options.
     *
     * Spring AI 2.0 no longer merges a model's configured options into a prompt that already
     * carries options (OpenAiChatModel/AnthropicChatModel `buildRequestPrompt` returns the
     * prompt unchanged when `getOptions() != null`). Because Embabel always supplies
     * per-request options — and provider option types coerce a null model to a hard-coded
     * default in their constructor (e.g. `gpt-5-mini`, `claude-haiku-4-5`) — the selected
     * model would otherwise be ignored on every call. Binding the [ChatModel]'s own
     * configured model restores the pre-2.0 merge semantics for every provider at once.
     */
    private fun bindConfiguredModel(chatOptions: ChatOptions): ChatOptions =
        bindModel(chatOptions, chatModel.options?.model)

    override fun createMessageSender(options: LlmOptions): LlmMessageSender {
        val chatOptions = bindConfiguredModel(optionsConverter.convertOptions(options))
        return SpringAiLlmMessageSender(
            chatModel = chatModel,
            chatOptions = chatOptions,
            toolResponseContentAdapter = toolResponseContentAdapter,
            nativeStructuredOutputConfigurer = nativeStructuredOutputConfigurer,
            nativeSupport = nativeSupport,
            llmMetadata = this,
        )
    }

    override fun createMessageStreamer(options: LlmOptions): LlmMessageStreamer {
        val chatOptions = bindConfiguredModel(optionsConverter.convertOptions(options))
        val chatClient = ChatClient.create(chatModel)
        return SpringAiLlmMessageStreamer(chatClient, chatOptions)
    }

    override fun supportsStreaming(): Boolean = StreamingCapabilityVerifier.supportsStreaming(chatModel)

    override fun supportsThinking(): Boolean = thinkingSupported

    override fun withKnowledgeCutoffDate(date: LocalDate): SpringAiLlmService =
        copy(
            knowledgeCutoffDate = date,
            promptContributors = promptContributors + KnowledgeCutoffDate(date)
        )

    override fun withPromptContributor(promptContributor: PromptContributor): SpringAiLlmService =
        copy(promptContributors = promptContributors + promptContributor)

    /**
     * Returns a copy with a different options converter.
     */
    fun withOptionsConverter(converter: OptionsConverter<*>): SpringAiLlmService =
        copy(optionsConverter = converter)
}

/**
 * Binds [model] onto [options], preserving the concrete provider option type and all other
 * fields via each provider's overridden `mutate()` (dynamic dispatch keeps e.g.
 * `OpenAiChatOptions`/`AnthropicChatOptions` intact). Returns [options] unchanged when
 * [model] is null or blank, so models that expose no configured default (bare test doubles,
 * exotic providers) keep the converter's output.
 */
internal fun bindModel(options: ChatOptions, model: String?): ChatOptions =
    if (model.isNullOrBlank()) options else options.mutate().model(model).build()
