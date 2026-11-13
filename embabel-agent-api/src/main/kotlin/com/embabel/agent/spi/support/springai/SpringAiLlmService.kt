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
import com.embabel.agent.spi.support.streaming.InternalStreamingApi
import com.embabel.agent.spi.support.streaming.StreamingCapabilityDetector
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.model.*
import com.embabel.common.ai.prompt.KnowledgeCutoffDate
import com.embabel.common.ai.prompt.PromptContributor
import tools.jackson.databind.annotation.JsonSerialize
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import java.time.LocalDate
import kotlin.jvm.internal.DefaultConstructorMarker

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
 * @param optionsConverter Function to convert [LlmOptions] to Spring AI ChatOptions.
 *        Do not call [OptionsConverter.convertOptions] directly — use [SpringAiLlmService.convertOptions]
 *        which also stamps the configured model name.
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
 * @param nativeThinkingExtractor Extracts native thinking blocks from the raw ChatResponse.
 *        Defaults to no-op; Anthropic wires [com.embabel.agent.anthropic.AnthropicNativeThinkingExtractor].
 * @param promptTokensExcludeCache Whether the chat model reports prompt-cache reads and writes
 *        on top of Spring AI's promptTokens (Anthropic) rather than within it (OpenAI).
 */
@JsonSerialize(`as` = LlmMetadata::class)
data class SpringAiLlmService @JvmOverloads constructor(
    override val name: String,
    override val provider: String,
    @get:JvmName("getChatModel")
    val chatModel: ChatModel,
    val optionsConverter: OptionsConverter = DefaultOptionsConverter,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val promptContributors: List<PromptContributor> =
        buildList { knowledgeCutoffDate?.let { add(KnowledgeCutoffDate(it)) } },
    override val pricingModel: PricingModel? = null,
    val thinkingSupported: Boolean = false,
    val toolResponseContentAdapter: ToolResponseContentAdapter = ToolResponseContentAdapter.PASSTHROUGH,
    val nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer =
        SpringAiNativeStructuredOutputConfigurer.NOOP,
    val nativeThinkingExtractor: SpringAiNativeThinkingExtractor =
        SpringAiNativeThinkingExtractor.NOOP,
    val nativeSupport: NativeSupport? = null,
    val promptTokensExcludeCache: Boolean = false,
) : LlmService<SpringAiLlmService>, AiModel<ChatModel> {

    /**
     * Binary compatibility: the synthetic constructor that Kotlin callers compiled before
     * [promptTokensExcludeCache] link to when they leave arguments out. Bit `i` of [mask] set means
     * argument `i` was omitted, so it takes its default.
     */
    @Suppress("UNUSED_PARAMETER")
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(
        name: String, provider: String, chatModel: ChatModel, optionsConverter: OptionsConverter?,
        knowledgeCutoffDate: LocalDate?, promptContributors: List<PromptContributor>?, pricingModel: PricingModel?,
        thinkingSupported: Boolean, toolResponseContentAdapter: ToolResponseContentAdapter?,
        nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer?,
        nativeThinkingExtractor: SpringAiNativeThinkingExtractor?, nativeSupport: NativeSupport?,
        mask: Int, marker: DefaultConstructorMarker?,
    ) : this(
        name = name,
        provider = provider,
        chatModel = chatModel,
        optionsConverter = if (mask and (1 shl 3) != 0) DefaultOptionsConverter else optionsConverter!!,
        knowledgeCutoffDate = knowledgeCutoffDate,
        promptContributors = if (mask and (1 shl 5) != 0) {
            buildList { knowledgeCutoffDate?.let { add(KnowledgeCutoffDate(it)) } }
        } else promptContributors!!,
        pricingModel = pricingModel,
        thinkingSupported = thinkingSupported,
        toolResponseContentAdapter = if (mask and (1 shl 8) != 0) ToolResponseContentAdapter.PASSTHROUGH
            else toolResponseContentAdapter!!,
        nativeStructuredOutputConfigurer = if (mask and (1 shl 9) != 0) SpringAiNativeStructuredOutputConfigurer.NOOP
            else nativeStructuredOutputConfigurer!!,
        nativeThinkingExtractor = if (mask and (1 shl 10) != 0) SpringAiNativeThinkingExtractor.NOOP
            else nativeThinkingExtractor!!,
        nativeSupport = nativeSupport,
    )

    /** Binary compatibility: the `copy` callers compiled before [promptTokensExcludeCache] link to. Keeps it. */
    @Suppress("kotlin:S107") // binary-compat shim: the parameter list must match the old JVM signature
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    fun copy(
        name: String, provider: String, chatModel: ChatModel, optionsConverter: OptionsConverter,
        knowledgeCutoffDate: LocalDate?, promptContributors: List<PromptContributor>, pricingModel: PricingModel?,
        thinkingSupported: Boolean, toolResponseContentAdapter: ToolResponseContentAdapter,
        nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer,
        nativeThinkingExtractor: SpringAiNativeThinkingExtractor, nativeSupport: NativeSupport?,
    ): SpringAiLlmService = SpringAiLlmService(
        name, provider, chatModel, optionsConverter, knowledgeCutoffDate, promptContributors, pricingModel,
        thinkingSupported, toolResponseContentAdapter, nativeStructuredOutputConfigurer, nativeThinkingExtractor,
        nativeSupport, promptTokensExcludeCache,
    )

    /**
     * The underlying Spring AI ChatModel.
     * Exposed via [AiModel] interface for backward compatibility.
     */
    override val model: ChatModel get() = chatModel

    fun convertOptions(llmOptions: LlmOptions): ChatOptions =
        optionsConverter.convertOptions(llmOptions, name)

    override fun createMessageSender(options: LlmOptions): LlmMessageSender {
        return SpringAiLlmMessageSender(
            chatModel = chatModel,
            chatOptions = convertOptions(options),
            toolResponseContentAdapter = toolResponseContentAdapter,
            nativeStructuredOutputConfigurer = nativeStructuredOutputConfigurer,
            nativeThinkingExtractor = nativeThinkingExtractor,
            nativeSupport = nativeSupport,
            llmMetadata = this,
            promptTokensExcludeCache = promptTokensExcludeCache,
        )
    }

    override fun createMessageStreamer(options: LlmOptions): LlmMessageStreamer {
        return SpringAiLlmMessageStreamer(
            chatModel = chatModel,
            chatOptions = convertOptions(options),
            toolResponseContentAdapter = toolResponseContentAdapter,
            promptTokensExcludeCache = promptTokensExcludeCache,
        )
    }

    @OptIn(InternalStreamingApi::class)
    override fun supportsStreaming(): Boolean = StreamingCapabilityDetector.supportsStreaming(chatModel)

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
    fun withOptionsConverter(converter: OptionsConverter): SpringAiLlmService =
        copy(optionsConverter = converter)

    companion object {

        /**
         * Binary compatibility: the `copy$default` that Kotlin callers compiled before
         * [promptTokensExcludeCache] link to. Bit `i` of [mask] set means argument `i` was omitted,
         * so [self]'s value is kept.
         */
        @Suppress("UNUSED_PARAMETER", "kotlin:S107") // binary-compat shim: the parameter list must match the old JVM signature
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
        fun copyDefault(
            self: SpringAiLlmService, name: String?, provider: String?, chatModel: ChatModel?,
            optionsConverter: OptionsConverter?, knowledgeCutoffDate: LocalDate?,
            promptContributors: List<PromptContributor>?, pricingModel: PricingModel?, thinkingSupported: Boolean,
            toolResponseContentAdapter: ToolResponseContentAdapter?,
            nativeStructuredOutputConfigurer: SpringAiNativeStructuredOutputConfigurer?,
            nativeThinkingExtractor: SpringAiNativeThinkingExtractor?, nativeSupport: NativeSupport?,
            mask: Int, marker: Any?,
        ): SpringAiLlmService {
            fun omitted(i: Int) = mask and (1 shl i) != 0
            return self.copy(
                name = if (omitted(0)) self.name else name!!,
                provider = if (omitted(1)) self.provider else provider!!,
                chatModel = if (omitted(2)) self.chatModel else chatModel!!,
                optionsConverter = if (omitted(3)) self.optionsConverter else optionsConverter!!,
                knowledgeCutoffDate = if (omitted(4)) self.knowledgeCutoffDate else knowledgeCutoffDate,
                promptContributors = if (omitted(5)) self.promptContributors else promptContributors!!,
                pricingModel = if (omitted(6)) self.pricingModel else pricingModel,
                thinkingSupported = if (omitted(7)) self.thinkingSupported else thinkingSupported,
                toolResponseContentAdapter = if (omitted(8)) self.toolResponseContentAdapter else toolResponseContentAdapter!!,
                nativeStructuredOutputConfigurer = if (omitted(9)) self.nativeStructuredOutputConfigurer
                    else nativeStructuredOutputConfigurer!!,
                nativeThinkingExtractor = if (omitted(10)) self.nativeThinkingExtractor else nativeThinkingExtractor!!,
                nativeSupport = if (omitted(11)) self.nativeSupport else nativeSupport,
            )
        }
    }
}
