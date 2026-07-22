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
package com.embabel.agent.openai

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.util.loggerFor
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * Wrap this converter so every request-level [OpenAiChatOptions] carries the resolved
 * OpenAI-compatible wire model id for its [SpringAiLlmService].
 *
 * Spring AI 2.0 does not merge an [OpenAiChatModel]'s default model when runtime
 * [OpenAiChatOptions] are present, so OpenAI-compatible providers need the model bound
 * directly onto the request options produced by Embabel converters.
 *
 * @param model provider wire model id to force onto every converted request.
 * @throws IllegalArgumentException if [model] is blank or the delegate does not return
 * [OpenAiChatOptions].
 */
fun OptionsConverter<*>.withOpenAiModel(model: String): OptionsConverter<OpenAiChatOptions> {
    require(model.isNotBlank()) {
        "OpenAI-compatible model must not be blank"
    }
    return OptionsConverter { options ->
        val converted: Any? = convertOptions(options)
        require(converted is OpenAiChatOptions) {
            val type = converted?.let { it::class.qualifiedName } ?: "null"
            "OpenAI-compatible options converter must return OpenAiChatOptions for model '$model', but returned $type"
        }
        converted.mutate()
            .model(model)
            .build()
    }
}

/**
 * Options converter for GPT-5 models that don't support temperature adjustment.
 */
object Gpt5ChatOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions {
        if (options.temperature != null && options.temperature != 1.0) {
            loggerFor<Gpt5ChatOptionsConverter>().warn(
                "GPT-5 models do not support temperature settings other than default 1.0. You set {} but it will be ignored.",
                options.temperature,
            )
        }
        return OpenAiChatOptions.builder()
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
    }
}

/**
 * Standard options converter for OpenAI models that support all parameters.
 */
object StandardOpenAiOptionsConverter : OptionsConverter<OpenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OpenAiChatOptions {
        return OpenAiChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
    }
}
