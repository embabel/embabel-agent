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
package com.embabel.common.ai.model

import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

/**
 * Convert our LLM options to Spring AI ChatOptions.
 *
 * Prefer [convertOptions] with an explicit model over the no-model overload.
 * The no-model form is deprecated because provider converters carry hardcoded
 * default models that will silently go to the wire if not overridden.
 */
// TODO: update all converter implementations to override convertOptions(options, model) directly,
//  then remove the deprecated 1-arg form and the @Suppress below.
fun interface OptionsConverter<O : ChatOptions> {

    @Deprecated(
        message = "Provide the model explicitly — provider converters carry hardcoded defaults that bypass the configured model.",
        replaceWith = ReplaceWith("convertOptions(options, model)"),
    )
    fun convertOptions(options: LlmOptions): O

    @Suppress("DEPRECATION")
    fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        convertOptions(options).mutate().model(model).build()
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
