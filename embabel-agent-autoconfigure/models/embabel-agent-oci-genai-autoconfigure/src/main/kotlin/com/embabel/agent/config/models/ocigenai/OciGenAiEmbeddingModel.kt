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
package com.embabel.agent.config.models.ocigenai

import com.oracle.bmc.generativeaiinference.GenerativeAiInference
import com.oracle.bmc.generativeaiinference.model.DedicatedServingMode
import com.oracle.bmc.generativeaiinference.model.EmbedTextDetails
import com.oracle.bmc.generativeaiinference.model.OnDemandServingMode
import com.oracle.bmc.generativeaiinference.model.ServingMode
import com.oracle.bmc.generativeaiinference.requests.EmbedTextRequest
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.embedding.EmbeddingResponseMetadata
import org.springframework.ai.embedding.EmbeddingResultMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.metadata.EmptyUsage
import org.springframework.retry.support.RetryTemplate

class OciGenAiEmbeddingModel(
    private val client: GenerativeAiInference,
    private val options: OciGenAiEmbeddingOptions,
    private val retryTemplate: RetryTemplate,
) : EmbeddingModel {

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val dimensions = request.options?.dimensions ?: options.dimensions
        val details = EmbedTextDetails.builder()
            .inputs(request.instructions)
            .servingMode(servingMode(options))
            .compartmentId(options.compartmentId)
            .outputDimensions(dimensions)
            .truncate(options.truncate.toOci())
            .inputType(options.inputType?.toOci())
            .build()
        val response = retryTemplate.execute<com.oracle.bmc.generativeaiinference.responses.EmbedTextResponse, RuntimeException> {
            client.embedText(EmbedTextRequest.builder().embedTextDetails(details).build())
        }
        val result = response.embedTextResult
        val embeddings = result.embeddings.orEmpty().mapIndexed { index, values ->
            Embedding(values.map { it ?: 0.0f }.toFloatArray(), index, EmbeddingResultMetadata.EMPTY)
        }
        val usage = result.usage?.let { DefaultUsage(it.promptTokens, it.completionTokens, it.totalTokens, it) }
        return EmbeddingResponse(
            embeddings,
            EmbeddingResponseMetadata(result.modelId ?: options.model, usage ?: EmptyUsage()),
        )
    }

    override fun embed(document: Document): FloatArray =
        embed(getEmbeddingContent(document))

    private fun servingMode(options: OciGenAiEmbeddingOptions): ServingMode =
        when (options.servingMode) {
            OciGenAiServingMode.ON_DEMAND -> OnDemandServingMode.builder()
                .modelId(options.model)
                .build()

            OciGenAiServingMode.DEDICATED -> DedicatedServingMode.builder()
                .endpointId(requireNotNull(options.endpointId) { "OCI GenAI embedding endpointId is required for dedicated serving" })
                .build()
        }
}

data class OciGenAiEmbeddingOptions(
    val model: String,
    val compartmentId: String,
    val servingMode: OciGenAiServingMode = OciGenAiServingMode.ON_DEMAND,
    val endpointId: String? = null,
    val dimensions: Int? = null,
    val truncate: OciGenAiEmbeddingTruncate = OciGenAiEmbeddingTruncate.NONE,
    val inputType: OciGenAiEmbeddingInputType? = null,
)

private fun OciGenAiEmbeddingTruncate.toOci(): EmbedTextDetails.Truncate =
    when (this) {
        OciGenAiEmbeddingTruncate.NONE -> EmbedTextDetails.Truncate.None
        OciGenAiEmbeddingTruncate.START -> EmbedTextDetails.Truncate.Start
        OciGenAiEmbeddingTruncate.END -> EmbedTextDetails.Truncate.End
    }

private fun OciGenAiEmbeddingInputType.toOci(): EmbedTextDetails.InputType =
    when (this) {
        OciGenAiEmbeddingInputType.SEARCH_DOCUMENT -> EmbedTextDetails.InputType.SearchDocument
        OciGenAiEmbeddingInputType.SEARCH_QUERY -> EmbedTextDetails.InputType.SearchQuery
        OciGenAiEmbeddingInputType.CLASSIFICATION -> EmbedTextDetails.InputType.Classification
        OciGenAiEmbeddingInputType.CLUSTERING -> EmbedTextDetails.InputType.Clustering
    }
