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

import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.models.embeddings.CreateEmbeddingResponse
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.document.Document
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.embedding.AbstractEmbeddingModel
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.embedding.EmbeddingResponseMetadata
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext
import org.springframework.ai.embedding.observation.EmbeddingModelObservationDocumentation
import org.springframework.ai.model.EmbeddingUtils
import org.springframework.ai.observation.conventions.AiProvider
import org.springframework.ai.openai.OpenAiEmbeddingOptions

/**
 * Spring AI's `OpenAiEmbeddingModel`, reading the answer as OpenAI-COMPATIBLE providers send it.
 *
 * The OpenAI spec marks each item's `index` and the response's `usage` as required, and Spring AI
 * reads both unconditionally — the openai-java SDK then throws "`index` is not set" or "`usage` is
 * not set" for an answer without them. OpenAI sends both. Google's OpenAI-compatible endpoint sends
 * neither, so behind Spring AI's model a Gemini key could build no embedding service at all.
 *
 * So an item's index is its own when it has one and its position otherwise — the items arrive in
 * the order of the inputs, which is what the field records anyway — and usage is reported only when
 * the provider reported it. Everything else is as Spring AI does it: the same options merge, the
 * same per-request timeout, the same observation.
 */
internal class OpenAiCompatibleEmbeddingModel(
    private val client: OpenAIClient,
    private val options: OpenAiEmbeddingOptions,
    private val observationRegistry: ObservationRegistry,
    private val metadataMode: MetadataMode = MetadataMode.EMBED,
) : AbstractEmbeddingModel() {

    override fun embed(document: Document): FloatArray =
        call(EmbeddingRequest(listOf(document.getFormattedContent(metadataMode)), options))
            .results.firstOrNull()?.output ?: FloatArray(0)

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val merged = OpenAiEmbeddingOptions.builder().from(options).merge(request.options).build()
        val params = merged.toOpenAiCreateParams(request.instructions)
        val requestOptions = RequestOptions.builder().apply { merged.timeout?.let { timeout(it) } }.build()
        val context = EmbeddingModelObservationContext.builder()
            .embeddingRequest(EmbeddingRequest(request.instructions, merged))
            .provider(AiProvider.OPENAI.value())
            .build()
        return requireNotNull(
            EmbeddingModelObservationDocumentation.EMBEDDING_MODEL_OPERATION
                .observation(null, OBSERVATION_CONVENTION, { context }, observationRegistry)
                .observe<EmbeddingResponse> {
                    responseOf(client.embeddings().create(params, requestOptions))
                        .also { context.response = it }
                },
        ) { "the embedding observation returned nothing" }
    }

    private fun responseOf(response: CreateEmbeddingResponse): EmbeddingResponse {
        val embeddings = response.data().mapIndexed { position, item ->
            Embedding(
                EmbeddingUtils.toPrimitive(item.embedding()),
                Math.toIntExact(item._index().asKnown().orElse(position.toLong())),
            )
        }
        val metadata = EmbeddingResponseMetadata().apply {
            model = response._model().asKnown().orElse("")
            response._usage().asKnown().ifPresent { usage ->
                this.usage = DefaultUsage(
                    Math.toIntExact(usage.promptTokens()), 0, Math.toIntExact(usage.totalTokens()), usage,
                )
            }
        }
        return EmbeddingResponse(embeddings, metadata)
    }

    private companion object {
        val OBSERVATION_CONVENTION = DefaultEmbeddingModelObservationConvention()
    }
}
