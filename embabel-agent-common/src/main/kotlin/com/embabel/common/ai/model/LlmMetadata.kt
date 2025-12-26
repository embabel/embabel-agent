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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.time.LocalDate

/**
 * Metadata about a Large Language Model (LLM).
 */
@JsonDeserialize(`as` = LlmMetadataImpl::class)
interface LlmMetadata : ModelMetadata {

    override val type: ModelType get() = ModelType.LLM

    /**
     * The knowledge cutoff date of the model, if known.
     */
    val knowledgeCutoffDate: LocalDate?

    /**
     * The pricing model for the LLM, if known.
     */
    val pricingModel: PricingModel?

    companion object {

        /**
         * Creates a new instance of [LlmMetadata].
         *
         * @param name Name of the LLM.
         * @param provider Name of the provider, such as OpenAI.
         * @param knowledgeCutoffDate Knowledge cutoff date of the model, if known.
         * @param pricingModel Pricing model for the LLM, if known.
         */
        operator fun invoke(
            name: String,
            provider: String,
            knowledgeCutoffDate: LocalDate? = null,
            pricingModel: PricingModel? = null
        ): LlmMetadata = LlmMetadataImpl(name, provider, knowledgeCutoffDate, pricingModel)

        @JvmStatic
        @JvmOverloads
        fun create(
            name: String,
            provider: String,
            knowledgeCutoffDate: LocalDate? = null,
            pricingModel: PricingModel? = null
        ): LlmMetadata = LlmMetadataImpl(name, provider, knowledgeCutoffDate, pricingModel)
    }

}

private class LlmMetadataImpl(
    override val name: String,
    override val provider: String,
    override val knowledgeCutoffDate: LocalDate? = null,
    override val pricingModel: PricingModel? = null
) : LlmMetadata
