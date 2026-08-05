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
import com.embabel.common.util.loggerFor
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * Options converter for GPT-5 models, which take a token limit and no other hyperparameter.
 *
 * `temperature`, `top_p`, `presence_penalty` and `frequency_penalty` are refused as a block — each
 * for its presence alone, exactly like `max_tokens`:
 *
 * `400 Unsupported parameter: 'top_p' is not supported with this model.`
 *
 * so none of them is sent. The request carries the model id and, when the caller asked for one, a
 * limit on `max_completion_tokens`. Nothing else.
 *
 * The refusal is per model rather than per family, verified against the live API on 2026-08-05:
 * gpt-5, gpt-5-mini, gpt-5-nano, gpt-5.3-chat, gpt-5.5 and the gpt-5.6 tiers refuse all four, while
 * gpt-5.1, gpt-5.2 and the gpt-5.4 tiers accept them. Sending none of them to any of them keeps one
 * converter for the whole family: the models that would have honoured a `topP` lose it the way they
 * already lost `temperature`, and no caller gets a 400 for a parameter that was never essential.
 */
object Gpt5ChatOptionsConverter : OptionsConverter {

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        warnAboutIgnoredParameters(options)
        return OpenAiChatOptions.builder()
            .model(model)
            // Not maxTokens: the GPT-5 family rejects `max_tokens` with
            // "Unsupported parameter ... use 'max_completion_tokens' instead", and refuses the
            // request for the field's presence alone.
            .maxCompletionTokens(options.maxTokens)
            .build()
    }

    /**
     * Dropping these silently would read as the model ignoring them; refusing the call outright
     * would cost the caller an answer over a parameter that was never essential.
     */
    private fun warnAboutIgnoredParameters(options: LlmOptions) {
        val ignored = buildList {
            // The default temperature is what the model uses anyway, so asking for it is not a
            // request that went unanswered.
            options.temperature?.takeIf { it != 1.0 }?.let { add("temperature=$it") }
            options.topP?.let { add("topP=$it") }
            options.presencePenalty?.let { add("presencePenalty=$it") }
            options.frequencyPenalty?.let { add("frequencyPenalty=$it") }
        }
        if (ignored.isNotEmpty()) {
            loggerFor<Gpt5ChatOptionsConverter>().warn(
                "This model rejects sampling parameters outright, so the following are ignored rather than sent: {}",
                ignored.joinToString(", "),
            )
        }
    }
}

/**
 * Standard options converter for OpenAI models that support all parameters.
 *
 * Keeps `maxTokens`: the models routed here — the GPT-4 family and OpenAI-compatible providers —
 * accept it, and several do not know `max_completion_tokens` at all.
 */
object StandardOpenAiOptionsConverter : OptionsConverter {

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        return OpenAiChatOptions.builder()
            .model(model)
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .build()
    }
}
