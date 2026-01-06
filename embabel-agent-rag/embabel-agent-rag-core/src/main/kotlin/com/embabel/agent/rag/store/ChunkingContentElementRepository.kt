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

import com.embabel.agent.rag.ingestion.RetrievableEnhancer
import com.embabel.agent.rag.model.ContentRoot
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.Retrievable

/**
 * Result of successfully deleting the content root with the given uri
 * and its descendants
 * @param rootUri the uri of the deleted root
 * @param deletedCount number of content elements deleted
 */
data class DocumentDeletionResult(
    val rootUri: String,
    val deletedCount: Int,
)

/**
 * ContentElementRepository that can chunk and persist documents,
 * we well as remove them.
 */
interface ChunkingContentElementRepository : ContentElementRepository {

    /**
     * Write the given content root and its children to the underlying store.
     * @return list of chunk ids
     */
    fun writeAndChunkDocument(root: NavigableDocument): List<String>

    /**
     * Delete the document root with this uri
     * Return null if no such document
     */
    fun deleteRootAndDescendants(uri: String): DocumentDeletionResult?

    fun findContentRootByUri(uri: String): ContentRoot?

    /**
     * Does a root with the given uri exist?
     */
    fun existsRootWithUri(uri: String): Boolean = findContentRootByUri(uri) != null

    /**
     * List of enhancers
     */
    val enhancers: List<RetrievableEnhancer>

    fun <T : Retrievable> enhance(retrievable: T): T {
        // TODO need context to do this properly
        var enhanced = retrievable
        for (enhancer in enhancers) {
            enhanced = enhancer.enhance(retrievable)
        }
        return enhanced
    }

    /**
     * Process new retrievables after they have been saved to the store.
     *
     * This method is called by [AbstractChunkingContentElementRepository.writeAndChunkDocument]
     * after chunks are created and saved. The default implementation in
     * [AbstractChunkingContentElementRepository]:
     * 1. Filters to extract [com.embabel.agent.rag.model.Chunk] instances
     * 2. Generates embeddings in batches (if an embedding service is configured)
     * 3. Delegates to [AbstractChunkingContentElementRepository.persistChunksWithEmbeddings]
     *
     * @param retrievables List of retrievables to process; typically chunks from document ingestion
     */
    fun onNewRetrievables(
        retrievables: List<Retrievable>,
    )

}
