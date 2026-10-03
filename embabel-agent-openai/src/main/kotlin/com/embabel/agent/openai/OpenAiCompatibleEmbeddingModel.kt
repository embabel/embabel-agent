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
import org.slf4j.LoggerFactory
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
import java.util.concurrent.ConcurrentHashMap

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
 * the provider reported it. The first answer that leaves either out is logged at info, and each
 * one after it at debug.
 *
 * The request side is as Spring AI does it: the same options merge, the same per-request timeout,
 * the same observation, and a document embedded as its content under [metadataMode], whether it
 * arrives alone or in a batch.
 */
internal class OpenAiCompatibleEmbeddingModel(
    private val client: OpenAIClient,
    private val options: OpenAiEmbeddingOptions,
    private val observationRegistry: ObservationRegistry,
    private val metadataMode: MetadataMode = MetadataMode.EMBED,
) : AbstractEmbeddingModel() {

    private val logger = LoggerFactory.getLogger(javaClass)

    // The batch path asks for a document's content here, and its default is the bare text: without
    // this, a document embedded in a batch would lose the metadata one embedded alone keeps.
    override fun getEmbeddingContent(document: Document): String =
        document.getFormattedContent(metadataMode)

    override fun embed(document: Document): FloatArray =
        call(EmbeddingRequest(listOf(getEmbeddingContent(document)), options))
            .results.firstOrNull()?.output ?: FloatArray(0)

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val merged = OpenAiEmbeddingOptions.builder().from(options).merge(request.options).build()
        val params = merged.toOpenAiCreateParams(request.instructions)
        val requestOptions = RequestOptions.builder().timeout(merged.timeout).build()
        val context = EmbeddingModelObservationContext.builder()
            .embeddingRequest(EmbeddingRequest(request.instructions, merged))
            .provider(AiProvider.OPENAI.value())
            .build()
        return EmbeddingModelObservationDocumentation.EMBEDDING_MODEL_OPERATION
            .observation(null, OBSERVATION_CONVENTION, { context }, observationRegistry)
            .observe<EmbeddingResponse> {
                responseOf(client.embeddings().create(params, requestOptions))
                    .also { context.response = it }
            }
    }

    private fun responseOf(response: CreateEmbeddingResponse): EmbeddingResponse {
        reportOmissions(response)
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

    /**
     * Says which required fields this answer left out, so the fallback taken is on the record:
     * once per model at info, since a provider that omits a field omits it every time, then at debug.
     *
     * Once per MODEL rather than per instance: validating a key builds one instance for the probe
     * and another for use, and the platform builds one per key, so a per-instance flag said the same
     * thing at least twice for every key connected. A different model still gets its own line.
     */
    private fun reportOmissions(response: CreateEmbeddingResponse) {
        val omitted = listOfNotNull(
            "index".takeIf { response.data().any { it._index().asKnown().isEmpty } },
            "usage".takeIf { response._usage().asKnown().isEmpty },
        )
        if (omitted.isEmpty()) return
        val model = response._model().asKnown().orElse(options.model)
        if (OMISSIONS_REPORTED.add(options.model ?: model)) {
            logger.info(OMISSION_MESSAGE, model, omitted)
        } else {
            logger.debug(OMISSION_MESSAGE, model, omitted)
        }
    }

    private companion object {
        const val OMISSION_MESSAGE =
            "Embedding answer from '{}' omits {}: index falls back to position, usage is reported only when sent"

        /** Models whose omissions have been reported at info, for this process. */
        val OMISSIONS_REPORTED: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val OBSERVATION_CONVENTION = DefaultEmbeddingModelObservationConvention()
    }
}
