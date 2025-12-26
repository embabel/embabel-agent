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
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.springframework.ai.chat.model.ChatModel
import java.time.LocalDate

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
@JsonSerialize(`as` = LlmMetadata::class)
data class Llm @JvmOverloads constructor(
    override val name: String,
    override val provider: String,
    override val model: ChatModel,
    val optionsConverter: OptionsConverter<*> = DefaultOptionsConverter,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val promptContributors: List<PromptContributor> =
        buildList { knowledgeCutoffDate?.let { add(KnowledgeCutoffDate(it)) } },
    override val pricingModel: PricingModel? = null,
) : AiModel<ChatModel>, LlmMetadata, PromptContributorConsumer {

    fun withOptionsConverter(converter: OptionsConverter<*>): Llm =
        this.copy(optionsConverter = converter)

    fun withKnowledgeCutoffDate(date: LocalDate): Llm =
        this.copy(
            knowledgeCutoffDate = date,
            promptContributors = promptContributors + KnowledgeCutoffDate(date)
        )

    fun withPromptContributor(promptContributor: PromptContributor): Llm =
        this.copy(promptContributors = promptContributors + promptContributor)
}
