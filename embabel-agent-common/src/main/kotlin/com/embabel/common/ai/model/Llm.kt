/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.common.ai.prompt.KnowledgeCutoffDate
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptContributorConsumer
import com.embabel.common.util.ComputerSaysNoSerializer
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import java.time.LocalDate

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

/**
 * Wraps a Spring AI ChatModel exposing an LLM.
 * @param name name of the LLM
 * @param provider name of the provider, such as OpenAI
 * @param model the Spring AI ChatModel to call
 * @param optionsConverter function to convert LLM options to Spring AI ChatOptions
 * @param knowledgeCutoffDate model's knowledge cutoff date, if known
 * @param promptContributors list of prompt contributors to be used with this model.
 * Knowledge cutoff is most important and will be included if knowledgeCutoffDate is not null.
 * @param pricingModel if known for this LLM
 */
@JsonSerialize(using = ComputerSaysNoSerializer::class)
data class Llm(
    override val name: String,
    override val provider: String,
    override val model: ChatModel,
    val optionsConverter: OptionsConverter<*>,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val promptContributors: List<PromptContributor> =
        buildList { knowledgeCutoffDate?.let { add(KnowledgeCutoffDate(it)) } },
    override val pricingModel: PricingModel? = null,
) : AiModel<ChatModel>, LlmMetadata, PromptContributorConsumer
