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
 * Implementations must override [convertOptions] and include the given [model]
 * in the returned [ChatOptions] so that the configured model name is always
 * stamped onto outgoing requests.
 */
interface OptionsConverter {

    /**
     * Convert [LlmOptions] to provider-specific [ChatOptions], stamping the
     * given [model] name onto the result.
     */
    fun convertOptions(options: LlmOptions, model: String): ChatOptions
}

/**
 * Do not use in production code, this is just a lowest common denominator
 * and example.
 */
object DefaultOptionsConverter : OptionsConverter {
    override fun convertOptions(options: LlmOptions, model: String): ChatOptions =
        ToolCallingChatOptions.builder()
            .model(model)
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
}
