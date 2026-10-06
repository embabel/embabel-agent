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
import org.slf4j.event.Level
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
 * An embedding model for OpenAI-COMPATIBLE providers. It does what Spring AI's
 * `OpenAiEmbeddingModel` does, except that it accepts a response with two fields missing.
 *
 * The OpenAI spec requires an `index` on each embedding and a `usage` block on the response.
 * OpenAI sends both. Google's OpenAI-compatible endpoint sends neither. Spring AI reads both
 * without checking, so the openai-java SDK throws "`index` is not set" or "`usage` is not set",
 * and a Gemini key could not build an embedding service.
 *
 * This class handles the missing fields as follows:
 * - `index` missing: the embedding's position in the response list is used. Embeddings are
 *   returned in the same order as the inputs, so the position is the same number.
 * - `usage` missing: the response metadata carries no usage.
 *
 * A response with a missing field is logged: at info the first time for a model, at debug after.
 *
 * Sending the request is unchanged from Spring AI: the same options merge, the same per-request
 * timeout, the same observation, and the same text for a document ([metadataMode] decides how
 * much of its metadata is included).
 */
internal class OpenAiCompatibleEmbeddingModel(
    private val client: OpenAIClient,
    private val options: OpenAiEmbeddingOptions,
    private val observationRegistry: ObservationRegistry,
    private val metadataMode: MetadataMode = MetadataMode.EMBED,
) : AbstractEmbeddingModel() {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Returns the text sent to the provider for a document: its content plus the metadata that
    // [metadataMode] selects. The inherited default returns the content only. The batch path,
    // embed(List<Document>, ...), calls this method, so the override makes a batch send the same
    // text as embed(Document) below.
    override fun getEmbeddingContent(document: Document): String =
        document.getFormattedContent(metadataMode)

    override fun embed(document: Document): FloatArray =
        call(EmbeddingRequest(listOf(getEmbeddingContent(document)), options))
            .results.firstOrNull()?.output ?: FloatArray(0)

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        // Start from the options this model was built with, then apply the request's options on
        // top. A non-null value on the request (model, dimensions, user, ...) replaces the
        // configured one, and a null leaves it as configured. Custom headers and extra body are
        // maps: both sides are combined, and the request's entry wins on a shared key.
        val merged = OpenAiEmbeddingOptions.builder().from(options).merge(request.options).build()
        val params = merged.toOpenAiCreateParams(request.instructions)
        val requestOptions = RequestOptions.builder().timeout(merged.timeout).build()
        val context = EmbeddingModelObservationContext.builder()
            .embeddingRequest(EmbeddingRequest(request.instructions, merged))
            .provider(AiProvider.OPENAI.value())
            .build()
        // The first argument is a custom observation convention. This class has none, so it
        // passes null and Micrometer uses the default convention given as the second argument.
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
                // The index if the provider sent one, otherwise the position in the list. The
                // SDK holds it as a Long and Spring AI takes an Int: toIntExact converts, and
                // throws ArithmeticException on a value too large for an Int instead of
                // silently wrapping as toInt() would.
                Math.toIntExact(item._index().asKnown().orElse(position.toLong())),
            )
        }
        val metadata = EmbeddingResponseMetadata().apply {
            model = response._model().asKnown().orElse("")
            response._usage().asKnown().ifPresent { usage ->
                // Token counts are Long in the SDK and Int in Spring AI, converted as above.
                this.usage = DefaultUsage(
                    Math.toIntExact(usage.promptTokens()), 0, Math.toIntExact(usage.totalTokens()), usage,
                )
            }
        }
        return EmbeddingResponse(embeddings, metadata)
    }

    /**
     * Logs which of `index` and `usage` the response left out.
     *
     * Logged at info the first time for a model and at debug after that, because a provider that
     * leaves a field out does so on every response.
     *
     * "First time" is tracked per model name for the whole process, not per instance of this
     * class. Validating a key builds two instances (one to probe, one to use), so a per-instance
     * flag would log the same line at info twice for every key.
     */
    private fun reportOmissions(response: CreateEmbeddingResponse) {
        val omitted = listOfNotNull(
            "index".takeIf { response.data().any { it._index().asKnown().isEmpty } },
            "usage".takeIf { response._usage().asKnown().isEmpty },
        )
        if (omitted.isEmpty()) return
        // The model name as configured, since that is the name the user chose. The response's
        // own `model` is used only when none was configured.
        val model = options.model ?: response._model().asKnown().orElse("unknown")
        val firstForModel = OMISSIONS_REPORTED.add(model)
        logger.atLevel(if (firstForModel) Level.INFO else Level.DEBUG).log(OMISSION_MESSAGE, model, omitted)
    }

    private companion object {
        const val OMISSION_MESSAGE =
            "Embedding answer from '{}' omits {}: index falls back to position, usage is reported only when sent"

        /** Models whose omissions have been reported at info, for this process. */
        val OMISSIONS_REPORTED: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val OBSERVATION_CONVENTION = DefaultEmbeddingModelObservationConvention()
    }
}
