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

import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.spi.InternalExtensionApi
import org.jetbrains.annotations.ApiStatus
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions

private const val OPENAI_REASONING_EFFORT_EXTENSION = "openai.reasoningEffort"

/** Set the OpenAI reasoning effort without changing other LLM options. */
@OptIn(InternalExtensionApi::class)
fun LlmOptions.withOpenAiReasoningEffort(effort: String): LlmOptions =
    withExtension(OPENAI_REASONING_EFFORT_EXTENSION, effort)

/** Return the OpenAI reasoning effort, or null when the provider default applies. */
@OptIn(InternalExtensionApi::class)
fun LlmOptions.getOpenAiReasoningEffort(): String? =
    getExtension(OPENAI_REASONING_EFFORT_EXTENSION)

/**
 * Adds the configured reasoning effort to what [delegate] converts. Wraps only converters for
 * OpenAI's own endpoint: OpenAI-compatible providers share the delegates and may reject the field.
 */
@ApiStatus.Internal
data class OpenAiReasoningEffortOptionsConverter(private val delegate: OptionsConverter) : OptionsConverter {

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        val converted = delegate.convertOptions(options, model)
        val effort = options.getOpenAiReasoningEffort() ?: return converted
        return (converted as? OpenAiChatOptions)?.mutate()?.reasoningEffort(effort)?.build() ?: converted
    }
}
