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
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.Retrievable
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
            .map { it.withAdditionalMetadata(rootMetadata + it.metadata) }
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
