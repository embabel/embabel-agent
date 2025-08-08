package com.embabel.common.ai.model

import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

/**
 * Convert our LLM options to Spring AI ChatOptions
 */
fun interface OptionsConverter<O : ChatOptions> {
    fun convertOptions(options: LlmOptions): O
}

/**
 * Do not use in production code, this is just a lowest common denominator
 * and example
 */
object DefaultOptionsConverter : OptionsConverter<ChatOptions> {
    override fun convertOptions(options: LlmOptions): ChatOptions =
        ToolCallingChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .topP(options.topP)
            .build()
}