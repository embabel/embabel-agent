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
package com.embabel.agent.rag.store

/**
 * Outcome of [EmbeddingBatchGenerator.embedInBatches].
 *
 * @property embeddings chunk id to vector, for every chunk that was embedded
 * @property missingChunkIds ids of the chunks that could not be embedded, in input order
 * @property cause the failure that left the most recent chunk in [missingChunkIds] unembedded; a failure
 * that a retry recovered from is never reported here. Non-null whenever [missingChunkIds] is not empty
 */
class EmbeddingBatchResult(
    val embeddings: Map<String, FloatArray>,
    val missingChunkIds: List<String>,
    val cause: Throwable?,
) {

    init {
        require(missingChunkIds.isEmpty() || cause != null) {
            "${missingChunkIds.size} chunks are missing embeddings but no cause was given"
        }
    }

    val isComplete: Boolean get() = missingChunkIds.isEmpty()
}
