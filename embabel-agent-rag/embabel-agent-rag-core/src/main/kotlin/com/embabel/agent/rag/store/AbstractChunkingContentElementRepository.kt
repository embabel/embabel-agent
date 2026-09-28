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
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.ai.model.EmbeddingService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Convenience base class for [ChunkingContentElementRepository] implementations.
 *
 * This abstract class provides the [writeAndChunkDocument] template method for
 * chunking documents and persisting their content elements. It delegates
 * processing of new retrievables to subclasses via the abstract [onNewRetrievables] method.
 *
 * ## Subclass Contract
 *
 * Subclasses must implement the following abstract methods:
 *
 * - [onNewRetrievables]: Process new retrievables (e.g., generate embeddings, index for search)
 * - [createInternalRelationships]: Create relationships between structural elements (e.g., in a graph database)
 * - [commit]: Commit changes after a write operation
 * - [save]: Persist individual content elements (inherited from [ContentElementRepository])
 *
 * A subclass that embeds chunks in [onNewRetrievables] must do so through [embedAndPersist].
 * It persists every chunk, with or without a vector, before throwing [EmbeddingIncompleteException],
 * so the chunks that did embed are never lost and the caller is always told about the ones that did not.
 *
 * ## Embedding Support
 *
 * For repositories that require embedding generation, extend
 * [EmbeddingAwareChunkingContentElementRepository] instead, which provides a concrete
 * [onNewRetrievables] implementation with batched embedding generation.
 *
 * @param chunkerConfig Configuration for content chunking
 * @param chunkTransformer Transformer applied to chunks during chunking
 */
abstract class AbstractChunkingContentElementRepository(
    protected val chunkerConfig: ContentChunker.Config,
    protected val chunkTransformer: ChunkTransformer,
) : ChunkingContentElementRepository {

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    /**
     * Will call save on the root and all descendants.
     * The database only needs to store each descendant and link by id,
     * rather than otherwise consider the entire structure.
     *
     * @throws EmbeddingIncompleteException if some chunks could not be embedded. It is thrown
     * after the document and all its chunks have been saved and committed.
     */
    final override fun writeAndChunkDocument(root: NavigableDocument): List<String> {
        logger.info(
            "Writing and chunking document {} with uri {} and title '{}' using config {}",
            root.id,
            root.uri,
            root.title,
            chunkerConfig
        )
        val chunker = ContentChunker(chunkerConfig, chunkTransformer)
        val rootMetadata = root.metadata
        val chunks = chunker.chunk(root)
            .map { it.withAdditionalMetadata(rootMetadata) }
            .map { enhance(it) }
        logger.info(
            "Chunked document {} into {} chunks",
            root.id,
            chunks.size,
        )
        save(root)
        root.descendants().forEach { save(it) }
        val descendantsIncomplete = embeddingIncomplete {
            onNewRetrievables(root.descendants().filterIsInstance<Retrievable>())
        }
        chunks.forEach { save(it) }
        val chunksIncomplete = embeddingIncomplete { onNewRetrievables(chunks) }
        createInternalRelationships(root)
        commit()
        logger.info(
            "Wrote and chunked document {} with {} chunks",
            root.id,
            chunks.size,
        )
        combine(descendantsIncomplete, chunksIncomplete)?.let { throw it }
        return chunks.map { it.id }
    }

    /**
     * Embed the chunks with [chunkIds] again and commit, replacing each stored copy.
     *
     * Use this with [EmbeddingIncompleteException.missingChunkIds] to complete a write that could not
     * embed every chunk. Ids with no stored chunk are logged and skipped.
     *
     * @return the ids of the chunks that were passed to [onNewRetrievables]
     * @throws EmbeddingIncompleteException if some chunks still could not be embedded. It is thrown
     * after the others have been committed.
     */
    fun reembedChunks(chunkIds: List<String>): List<String> {
        val chunks = findAllChunksById(chunkIds).toList()
        if (chunks.size < chunkIds.size) {
            val found = chunks.map { it.id }.toSet()
            logger.warn("No stored chunk to re-embed for ids {}", chunkIds.filterNot { it in found })
        }
        val incomplete = embeddingIncomplete { onNewRetrievables(chunks) }
        commit()
        incomplete?.let { throw it }
        return chunks.map { it.id }
    }

    /**
     * Embed the chunks among [retrievables] with [embeddingService], pass them to [persist], then
     * throw [EmbeddingIncompleteException] if any could not be embedded.
     *
     * [persist] receives every chunk and a map of chunk id to vector. A chunk missing from the map
     * could not be embedded and must be stored without a vector. With no [embeddingService], no
     * chunk is embedded and nothing is thrown.
     */
    protected fun embedAndPersist(
        retrievables: List<Retrievable>,
        embeddingService: EmbeddingService?,
        persist: (chunks: List<Chunk>, embeddings: Map<String, FloatArray>) -> Unit,
    ) {
        val chunks = retrievables.filterIsInstance<Chunk>()
        if (chunks.isEmpty()) {
            logger.debug("No chunks to process in {} retrievables", retrievables.size)
            return
        }
        val result = embeddingService?.let {
            EmbeddingBatchGenerator.embedInBatches(
                embeddingService = it,
                retrievables = chunks,
                batchSize = chunkerConfig.embeddingBatchSize,
                logger = logger,
            )
        }
        persist(chunks, result?.embeddings ?: emptyMap())
        result?.let { failIfEmbeddingsMissing(it) }
    }

    /**
     * Throw [EmbeddingIncompleteException] if [result] is missing embeddings.
     * Call after persisting the chunks, so the ones that embedded are stored.
     */
    protected fun failIfEmbeddingsMissing(result: EmbeddingBatchResult) {
        if (result.isComplete) return
        throw EmbeddingIncompleteException(
            missingChunkIds = result.missingChunkIds,
            embeddedCount = result.embeddings.size,
            cause = requireNotNull(result.cause) { "missing embeddings must carry the failure that caused them" },
        )
    }

    /**
     * Run [block], returning rather than throwing an [EmbeddingIncompleteException],
     * so the rest of the document is still written and committed before the caller is told.
     */
    private fun embeddingIncomplete(block: () -> Unit): EmbeddingIncompleteException? = try {
        block()
        null
    } catch (e: EmbeddingIncompleteException) {
        e
    }

    private fun combine(
        first: EmbeddingIncompleteException?,
        second: EmbeddingIncompleteException?,
    ): EmbeddingIncompleteException? = when {
        first == null -> second
        second == null -> first
        else -> EmbeddingIncompleteException(
            missingChunkIds = first.missingChunkIds + second.missingChunkIds,
            embeddedCount = first.embeddedCount + second.embeddedCount,
            cause = requireNotNull(first.cause),
        ).apply { addSuppressed(second) }
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
