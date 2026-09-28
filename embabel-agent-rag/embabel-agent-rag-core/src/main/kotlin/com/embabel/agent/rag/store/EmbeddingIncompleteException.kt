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
 * Thrown when some chunks could not be embedded, even after retrying their batch in smaller pieces.
 *
 * By the time this is thrown, the chunks that did embed have been persisted with their vectors,
 * and the chunks named in [missingChunkIds] have been persisted without one: they match text
 * search but never a vector search. The caller can retry or re-ingest them.
 *
 * Set [com.embabel.agent.rag.ingestion.ContentChunker.Config.failOnMissingEmbeddings] to false
 * to store the partial result without being told.
 *
 * @property missingChunkIds ids of the chunks stored without an embedding
 * @property embeddedCount number of chunks that were embedded
 */
class EmbeddingIncompleteException(
    val missingChunkIds: List<String>,
    val embeddedCount: Int,
    cause: Throwable,
) : RuntimeException(
    "${missingChunkIds.size} of ${embeddedCount + missingChunkIds.size} chunks could not be embedded",
    cause,
)
