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

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.ai.model.EmbeddingService

/**
 * Extension of [AbstractChunkingContentElementRepository] that generates embeddings
 * for chunks using an [EmbeddingService].
 *
 * This class implements [onNewRetrievables] by:
 * 1. Filtering incoming retrievables to extract [Chunk] instances
 * 2. Generating embeddings in configurable batches using [EmbeddingBatchGenerator]
 * 3. Delegating persistence to subclasses via [persistChunksWithEmbeddings]
 *
 * Use this base class when your repository always requires embedding support.
 * For repositories that support optional embeddings (e.g., text-only search),
 * extend [AbstractChunkingContentElementRepository] directly and implement
 * [onNewRetrievables] with your own logic.
 *
 * @param chunkerConfig Configuration for content chunking including [ContentChunker.Config.embeddingBatchSize]
 * @param chunkTransformer Transformer applied to chunks during chunking
 * @param embeddingService Embedding service for generating embeddings
 */
abstract class EmbeddingAwareChunkingContentElementRepository(
    chunkerConfig: ContentChunker.Config,
    chunkTransformer: ChunkTransformer,
    protected val embeddingService: EmbeddingService,
) : AbstractChunkingContentElementRepository(chunkerConfig, chunkTransformer) {

    final override fun onNewRetrievables(retrievables: List<Retrievable>) {
        val chunks = retrievables.filterIsInstance<Chunk>()
        if (chunks.isEmpty()) {
            logger.debug("No chunks to process in {} retrievables", retrievables.size)
            return
        }

        val embeddings = EmbeddingBatchGenerator.generateEmbeddingsInBatches(
            embeddingService = embeddingService,
            retrievables = chunks,
            batchSize = chunkerConfig.embeddingBatchSize,
            logger = logger,
        )
        persistChunksWithEmbeddings(chunks, embeddings)
    }

    /**
     * Persist chunks with their pre-generated embeddings to the underlying store.
     *
     * This method is called by [onNewRetrievables] after embedding generation is complete.
     * Subclasses should:
     * 1. Store each chunk in their backing storage (memory, database, index, etc.)
     * 2. Associate the embedding with each chunk (if available in the map)
     * 3. Handle the case where some embeddings may be missing (if a batch failed)
     *
     * @param chunks The chunks to persist; guaranteed to be non-empty when called
     * @param embeddings Map of chunk ID to embedding vector; may be missing entries
     *                   if embedding generation failed for some batches
     */
    protected abstract fun persistChunksWithEmbeddings(
        chunks: List<Chunk>,
        embeddings: Map<String, FloatArray>,
    )
}
