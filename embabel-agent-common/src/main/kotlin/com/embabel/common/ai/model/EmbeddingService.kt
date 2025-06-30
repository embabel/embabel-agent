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

import com.embabel.common.util.ComputerSaysNoSerializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.springframework.ai.embedding.EmbeddingModel
import java.time.LocalDate

@JsonDeserialize(`as` = EmbeddingServiceMetadataImpl::class)
interface EmbeddingServiceMetadata : ModelMetadata {

    override val type: ModelType get() = ModelType.EMBEDDING

    companion object {
        /**
         * Creates a new instance of [EmbeddingServiceMetadata].
         *
         * @param name Name of the LLM.
         * @param provider Name of the provider, such as OpenAI.
         */
        operator fun invoke(
            name: String,
            provider: String,
            knowledgeCutoffDate: LocalDate? = null,
            pricingModel: PricingModel? = null
        ): EmbeddingServiceMetadata = EmbeddingServiceMetadataImpl(name, provider)

        @JvmStatic
        fun create(
            name: String,
            provider: String,
        ): EmbeddingServiceMetadata = EmbeddingServiceMetadataImpl(name, provider)
    }
}

/**
 * Wraps a Spring AI EmbeddingModel exposing an embedding service.
 */
@JsonSerialize(using = ComputerSaysNoSerializer::class)
data class EmbeddingService(
    override val name: String,
    override val provider: String,
    override val model: EmbeddingModel,
) : AiModel<EmbeddingModel>, EmbeddingServiceMetadata

data class EmbeddingServiceMetadataImpl(
    override val name: String,
    override val provider: String,
) : EmbeddingServiceMetadata
