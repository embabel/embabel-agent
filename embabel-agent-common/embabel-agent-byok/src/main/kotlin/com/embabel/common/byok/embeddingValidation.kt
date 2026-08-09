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
package com.embabel.common.byok

import com.embabel.common.ai.model.EmbeddingService

/** Short, cheap text used to probe an embedding key. */
private const val EMBEDDING_VALIDATION_PROBE = "Hi"

/**
 * Validate a runtime-supplied embedding key by embedding one probe, and return a service that
 * knows the width the model actually produced.
 *
 * Provider-agnostic: [build] supplies the provider's own [EmbeddingService], called once to
 * probe and once more to produce the result with the observed width stamped on it.
 *
 * Stamping matters beyond saving a call. Spring AI's `AbstractEmbeddingModel.dimensions()`
 * caches, but resolves via `dimensions(this, "Test", "Hello World")` — passing the literal
 * `"Test"` as the model name, so its known-dimensions table can never hit and it always falls
 * through to a live `embed()`. Left lazy that happens at an arbitrary later moment and can fail
 * there. The probe already has the width; take it.
 *
 * The width is never taken from the caller: it is whatever the model returned. A caller writing
 * into an existing index should compare [EmbeddingService.dimensions] against that index's width
 * before storing anything — vectors of different widths cannot share an index. That check belongs
 * to whatever owns the index.
 *
 * This never sees the API key — [build] closes over whatever credential the provider needs — so
 * it cannot reject a blank one. Callers holding a key should check that themselves before getting
 * here, so an empty string fails with something actionable rather than as a provider error.
 *
 * @param model the embedding model to validate. Always explicit: unlike an LLM it is never
 * defaulted and never detected, because the width it commits an index to must not be decided by
 * whichever provider happens to answer first.
 * @param provider provider name, used only in the failure message
 * @param build builds the provider's service, given the width to stamp (null while probing)
 * @throws InvalidApiKeyException if the probe fails - an invalid key, an unreachable provider, a
 * model the key cannot use - or if the model returns no vector
 */
fun validatedEmbeddingService(
    model: String,
    provider: String,
    build: (configuredDimensions: Int?) -> EmbeddingService,
): EmbeddingService {
    // All three failure modes here mean the same thing to the caller - the model could not be
    // validated - so they share one exit rather than throwing from three places. Construction is
    // inside the try on purpose: building the probe service is where a malformed base URL or an
    // unusable credential surfaces for several providers, so it is part of validation rather than
    // separate from it. The message names the model: it is caller-supplied and mandatory, so a
    // typo in it arrives as the same provider error, and "invalid API key" alone would send
    // someone to re-check a key that was fine.
    val vector = try {
        build(null).embed(EMBEDDING_VALIDATION_PROBE)
            .also { require(it.isNotEmpty()) { "the model returned an empty vector" } }
    } catch (e: Exception) {
        throw InvalidApiKeyException(
            "Could not validate embedding model '$model' on $provider: ${e.message ?: "no detail"}",
        )
    }
    // Outside the try, unlike the call above. The credential and the model have both just been
    // proven to work, so anything thrown here is a bug in the builder and must not be reported as
    // a rejected key - that would send someone to re-check a key this function just validated.
    return build(vector.size)
}
