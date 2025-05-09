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
import com.embabel.common.core.types.HasInfoString
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.model.Model
import java.time.LocalDate

/**
 * Wraps a Spring AI model and allows metadata to be attached to a model
 */
interface AiModel<M : Model<*, *>> : HasInfoString {
    val name: String
    val provider: String
    val model: M

    override fun infoString(verbose: Boolean?): String =
        "name: $name, provider: $provider"
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
data class Llm(
    override val name: String,
    override val provider: String,
    override val model: ChatModel,
    val optionsConverter: OptionsConverter = DefaultOptionsConverter,
    val knowledgeCutoffDate: LocalDate? = null,
    override val promptContributors: List<PromptContributor> =
        buildList { knowledgeCutoffDate?.let { add(KnowledgeCutoffDate(it)) } },
    val pricingModel: PricingModel? = null,
) : AiModel<ChatModel>, PromptContributorConsumer

/**
 * Wraps a Spring AI EmbeddingModel exposing an embedding service.
 */
data class EmbeddingService(
    override val name: String,
    override val provider: String,
    override val model: EmbeddingModel,
) : AiModel<EmbeddingModel>
