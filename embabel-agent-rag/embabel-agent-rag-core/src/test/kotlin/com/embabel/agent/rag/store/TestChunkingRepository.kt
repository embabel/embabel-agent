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
import com.embabel.agent.rag.ingestion.RetrievableEnhancer
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.common.ai.model.EmbeddingService

/**
 * Test implementation of AbstractChunkingContentElementRepository
 */
class TestChunkingRepository(
    chunkerConfig: ContentChunker.Config,
    chunkTransformer: ChunkTransformer,
    embeddingService: EmbeddingService?,
) : AbstractChunkingContentElementRepository(chunkerConfig, chunkTransformer, embeddingService) {

    val persistedChunks = mutableListOf<Chunk>()
    val persistedEmbeddings = mutableMapOf<String, FloatArray>()
    val savedElements = mutableMapOf<String, ContentElement>()

    override val name: String = "test-repo"
    override val enhancers: List<RetrievableEnhancer> = emptyList()

    override fun persistChunksWithEmbeddings(chunks: List<Chunk>, embeddings: Map<String, FloatArray>) {
        persistedChunks.addAll(chunks)
        persistedEmbeddings.putAll(embeddings)
    }

    override fun createInternalRelationships(root: NavigableDocument) {
        // No-op for testing
    }

    override fun commit() {
        // No-op for testing
    }

    override fun save(element: ContentElement): ContentElement {
        savedElements[element.id] = element
        return element
    }

    override fun findById(id: String): ContentElement? = savedElements[id]

    override fun findChunksForEntity(entityId: String): List<Chunk> =
        persistedChunks.filter { it.parentId == entityId }

    override fun deleteRootAndDescendants(uri: String): DocumentDeletionResult? = null

    override fun findContentRootByUri(uri: String): ContentRoot? = null

    override fun findAllChunksById(chunkIds: List<String>): Iterable<Chunk> =
        persistedChunks.filter { it.id in chunkIds }

    override fun <C : ContentElement> findAll(clazz: Class<C>): Iterable<C> {
        TODO("Not yet implemented")
    }

    override fun info(): ContentElementRepositoryInfo = object : ContentElementRepositoryInfo {
        override val documentCount: Int = 0
        override val chunkCount: Int = persistedChunks.size
        override val contentElementCount: Int = savedElements.size
        override val hasEmbeddings: Boolean = embeddingService != null
        override val isPersistent: Boolean = false
    }
}
