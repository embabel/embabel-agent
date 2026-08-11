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

import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indent
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.LocalDate

@JsonDeserialize(`as` = EmbeddingServiceMetadataImpl::class)
interface EmbeddingServiceMetadata : ModelMetadata {

    override val type: ModelType get() = ModelType.EMBEDDING

    /**
     * The pricing model for the embedding service, if known.
     * Null for local models (Ollama, ONNX) where the cost is zero.
     */
    val pricingModel: PricingModel?

    companion object {
        /**
         * Creates a new instance of [EmbeddingServiceMetadata].
         *
         * @param name Name of the embedding service.
         * @param provider Name of the provider, such as OpenAI.
         * @param pricingModel Pricing model for the embedding service, if known.
         */
        operator fun invoke(
            name: String,
            provider: String,
            knowledgeCutoffDate: LocalDate? = null,
            pricingModel: PricingModel? = null,
        ): EmbeddingServiceMetadata = EmbeddingServiceMetadataImpl(name, provider, pricingModel)

        @JvmStatic
        @JvmOverloads
        fun create(
            name: String,
            provider: String,
            pricingModel: PricingModel? = null,
        ): EmbeddingServiceMetadata = EmbeddingServiceMetadataImpl(name, provider, pricingModel)
    }
}

/**
 * Embed text in vector space
 */
interface EmbeddingService : EmbeddingServiceMetadata, HasInfoString {

    /**
     * Embed a single text in vector space
     * @return embedding vector
     */
    fun embed(text: String): FloatArray

    /**
     * Embed multiple texts in vector space
     * Use this method for better performance when embedding multiple texts
     * @return list of embedding vectors corresponding to the input texts
     */
    fun embed(texts: List<String>): List<FloatArray>

    /**
     * Dimension of the embedding vectors produced by this model
     */
    val dimensions: Int

    /**
     * Whether this service is standing in for a model the deployment does not have a key for yet,
     * so nothing may be embedded and — above all — no dimension may be read from it.
     *
     * False for every real service, which is why it defaults. A placeholder overrides it, and
     * anything that would commit a vector index asks THIS rather than testing the service's type:
     * wrapping is common (event tracking decorates the configured service; applications add layers
     * to hot-swap the model or meter it), a wrapper around a placeholder is not itself a
     * placeholder, and Kotlin's `by` delegation forwards this property through any depth of
     * wrapping for free. A type test answers about the outermost layer only.
     *
     * A wrapper that implements [EmbeddingService] member by member rather than by delegation must
     * forward this too, or it will report "there is a model" on behalf of one that cannot embed.
     */
    val awaitingProviderKey: Boolean get() = false

    override fun infoString(verbose: Boolean?, indent: Int): String =
        "name: $name, provider: $provider".indent(indent)
}

data class EmbeddingServiceMetadataImpl(
    override val name: String,
    override val provider: String,
    override val pricingModel: PricingModel? = null,
) : EmbeddingServiceMetadata
