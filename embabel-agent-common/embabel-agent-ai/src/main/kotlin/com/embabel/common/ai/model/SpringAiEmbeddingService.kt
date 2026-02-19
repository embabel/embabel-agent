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

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.springframework.ai.embedding.EmbeddingModel

/**
 * Wraps a Spring AI EmbeddingModel exposing an embedding service.
 * @param configuredDimensions If provided, uses this value for [dimensions] instead of
 * making a live API call via [EmbeddingModel.dimensions]. This avoids startup failures
 * when the embedding endpoint is not available (e.g., Azure OpenAI deployments that
 * only expose chat completions).
 */
@JsonSerialize(`as` = EmbeddingServiceMetadata::class)
data class SpringAiEmbeddingService(
    override val name: String,
    override val provider: String,
    override val model: EmbeddingModel,
    val configuredDimensions: Int? = null,
) : EmbeddingService {

    override val dimensions get() = configuredDimensions ?: model.dimensions()

    override fun embed(text: String): FloatArray = model.embed(text)

    override fun embed(texts: List<String>): List<FloatArray> = model.embed(texts)
}
