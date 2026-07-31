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

import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.agent.spi.support.springai.SpringAiNativeStructuredOutputConfigurer
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.model.LlmMetadata
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * OpenAI-specific native structured-output bridge.
 *
 * This is a provider-module hook, not core logic. It currently translates the
 * neutral Embabel structured-output request into Spring AI's OpenAI option
 * objects, which Spring AI then serializes to the OpenAI payload shape.
 * It does not directly inject provider tags into the raw request payload yet.
 *
 * The YAML tag metadata in the model files is documented for now and is not yet
 * the source of truth for direct payload injection.
 */
internal object OpenAiNativeStructuredOutputConfigurer : SpringAiNativeStructuredOutputConfigurer {

    @Suppress("UNUSED_PARAMETER")
    override fun configure(
        options: ChatOptions,
        structuredOutput: StructuredOutputRequest?,
        nativeSupport: NativeSupport?,
        llm: LlmMetadata?,
    ): ChatOptions {
        val capability = nativeSupport?.structuredOutput ?: return options
        if (structuredOutput == null || capability.supported != true) {
            return options
        }
        if (options !is OpenAiChatOptions) {
            return options
        }
        if (capability.strategy != null && capability.strategy != "response_format") {
            return options
        }

        val responseFormat = ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(structuredOutput.schema)
            .build()

        return options.mutate()
            .responseFormat(responseFormat)
            .outputSchema(structuredOutput.schema)
            .build()
    }
}
