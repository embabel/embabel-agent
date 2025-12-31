/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.util.VisualizableTask
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Convenience base class for [ChunkingContentElementRepository] implementations.
 *
 * This abstract class provides a template method pattern for handling new retrievables:
 * 1. Filters incoming retrievables to extract [Chunk] instances
 * 2. Generates embeddings in configurable batches using the [embeddingService] (if provided)
 * 3. Delegates persistence to subclasses via [persistChunksWithEmbeddings]
 *
 * ## Subclass Contract
 *
 * Subclasses must implement the following abstract methods:
 *
 * - [persistChunksWithEmbeddings]: Store chunks with their pre-generated embeddings
 * - [createInternalRelationships]: Create relationships between structural elements (e.g., in a graph database)
 * - [commit]: Commit changes after a write operation
 * - [save]: Persist individual content elements (inherited from [ContentElementRepository])
 *
 * ## Embedding Generation
 *
 * When an [embeddingService] is provided:
 * - Embeddings are generated in batches of [ContentChunker.Config.embeddingBatchSize]
 * - Batch processing reduces API calls and improves throughput
 * - Failed batches are logged but don't prevent other batches from processing
 *
 * When no embedding service is provided:
 * - [persistChunksWithEmbeddings] is called with an empty embeddings map
 * - Subclasses should handle this case (e.g., skip embedding storage, use text-only search)
 *
 * @param chunkerConfig Configuration for content chunking including [ContentChunker.Config.embeddingBatchSize]
 * @param embeddingService Optional embedding service for generating embeddings; if null, no embeddings are generated
 *                         and [persistChunksWithEmbeddings] receives an empty map
 */
abstract class AbstractChunkingContentElementRepository(
    protected val chunkerConfig: ContentChunker.Config,
    protected val embeddingService: EmbeddingService?,
) : ChunkingContentElementRepository {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    init {
        if (embeddingService == null) {
            logger.warn("No embedding service configured; only text search will be supported.")
        }
    }

    /**
     * Template method implementation that generates embeddings in batches
     * and delegates persistence to subclasses.
     *
     * This method:
     * 1. Filters retrievables to extract only [Chunk] instances (structural elements are handled separately)
     * 2. Generates embeddings in batches using [embeddingService] if available
     * 3. Calls [persistChunksWithEmbeddings] to let subclasses store the chunks
     *
     * @param retrievables List of retrievables to process; only [Chunk] instances are processed
     */
    override fun onNewRetrievables(retrievables: List<Retrievable>) {
        val chunks = retrievables.filterIsInstance<Chunk>()
        if (chunks.isEmpty()) {
            logger.debug("No chunks to process in {} retrievables", retrievables.size)
            return
        }

        val embeddings = generateEmbeddingsInBatches(chunks)
        persistChunksWithEmbeddings(chunks, embeddings)
    }

    /**
     * Persist chunks with their pre-generated embeddings to the underlying store.
     *
     * This method is called by [onNewRetrievables] after embedding generation is complete.
     * Subclasses should:
     * 1. Store each chunk in their backing storage (memory, database, index, etc.)
     * 2. Associate the embedding with each chunk (if available in the map)
     * 3. Handle the case where embeddings map is empty (no embedding service configured)
     *
     * ## Example Implementation
     * ```kotlin
     * override fun persistChunksWithEmbeddings(chunks: List<Chunk>, embeddings: Map<String, FloatArray>) {
     *     chunks.forEach { chunk ->
     *         storage[chunk.id] = chunk
     *         embeddings[chunk.id]?.let { embedding ->
     *             embeddingIndex.store(chunk.id, embedding)
     *         }
     *     }
     * }
     * ```
     *
     * @param chunks The chunks to persist; guaranteed to be non-empty when called
     * @param embeddings Map of chunk ID to embedding vector; may be empty if no embedding service
     *                   is configured, or if embedding generation failed for all chunks
     */
    protected abstract fun persistChunksWithEmbeddings(
        chunks: List<Chunk>,
        embeddings: Map<String, FloatArray>,
    )

    private fun generateEmbeddingsInBatches(
        retrievables: List<Retrievable>,
    ): Map<String, FloatArray> {
        if (embeddingService == null) {
            return emptyMap()
        }

        val embeddings = mutableMapOf<String, FloatArray>()
        val batches = retrievables.chunked(chunkerConfig.embeddingBatchSize)
        val totalBatches = batches.size

        fun logProgress(current: Int) {
            val progress = VisualizableTask(
                name = "Generating embeddings",
                current = current,
                total = totalBatches
            )
            logger.info(progress.createProgressBar())
        }

        logProgress(0)

        batches.forEachIndexed { index, batch ->
            try {
                val texts = batch.map { it.embeddableValue() }
                val batchEmbeddings = embeddingService.embed(texts)

                batch.zip(batchEmbeddings).forEach { (chunk, embedding) ->
                    embeddings[chunk.id] = embedding
                }

                logProgress(index + 1)
            } catch (e: Exception) {
                logger.warn(
                    "Failed to generate embeddings for batch of {} chunks: {}",
                    batch.size,
                    e.message,
                    e
                )
            }
        }

        return embeddings
    }

    /**
     * Will call save on the root and all descendants.
     * The database only needs to store each descendant and link by id,
     * rather than otherwise consider the entire structure.
     */
    final override fun writeAndChunkDocument(root: NavigableDocument): List<String> {
        logger.info(
            "Writing and chunking document {} with uri {} and title '{}' using config {}",
            root.id,
            root.uri,
            root.title,
            chunkerConfig
        )
        val chunker = ContentChunker(chunkerConfig)
        val chunks = chunker.chunk(root)
            .map { enhance(it) }
        logger.info(
            "Chunked document {} into {} chunks",
            root.id,
            chunks.size,
        )
        save(root)
        root.descendants().forEach { save(it) }
        onNewRetrievables(root.descendants().filterIsInstance<Retrievable>())
        chunks.forEach { save(it) }
        onNewRetrievables(chunks)
        createInternalRelationships(root)
        commit()
        logger.info(
            "Wrote and chunked document {} with {} chunks",
            root.id,
            chunks.size,
        )
        return chunks.map { it.id }
    }

    /**
     * Create relationships between the structural elements in this content.
     *
     * This method is called after all content elements (document, sections, chunks) have been
     * saved via [save]. Subclasses can use this to create explicit relationships between
     * elements based on their hierarchical structure.
     *
     * ## When to Implement
     * - Graph databases: Create edges between document → sections → chunks
     * - Relational databases: May be a no-op if foreign keys are set in [save]
     * - Search indices: May be a no-op if relationships are implicit via IDs
     *
     * ## Example Implementation (Graph Database)
     * ```kotlin
     * override fun createInternalRelationships(root: NavigableDocument) {
     *     root.descendants().forEach { element ->
     *         element.parentId?.let { parentId ->
     *             graphDb.createEdge(parentId, element.id, "CONTAINS")
     *         }
     *     }
     * }
     * ```
     *
     * @param root The document root whose internal relationships should be created
     */
    protected abstract fun createInternalRelationships(root: NavigableDocument)

    /**
     * Commit changes after a write operation.
     *
     * This method is called at the end of [writeAndChunkDocument] after all content elements
     * have been saved and relationships created. Subclasses should ensure all pending changes
     * are durably persisted.
     *
     * ## When to Implement
     * - Search indices: Flush and commit the index writer
     * - Databases with transactions: Commit the transaction
     * - In-memory stores: May be a no-op
     *
     * ## Example Implementation (Lucene)
     * ```kotlin
     * override fun commit() {
     *     indexWriter.flush()
     *     indexWriter.commit()
     * }
     * ```
     */
    protected abstract fun commit()

}
