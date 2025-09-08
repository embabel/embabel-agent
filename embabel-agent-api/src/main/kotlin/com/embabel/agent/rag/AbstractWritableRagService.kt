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
package com.embabel.agent.rag

import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.ContentElementRepository
import com.embabel.agent.rag.ingestion.MaterializedDocument

/**
 * Convenience base class for WritableRagService implementations.
 */
// TODO inject configuration for chunking
abstract class AbstractWritableRagService : WritableRagService, ContentElementRepository {

    /**
     * Will call save on the root and all descendants.
     * The database only needs to store each descendant and link by id,
     * rather than otherwise consider the entire structure.
     */
    final override fun writeContent(root: MaterializedDocument): List<String> {
        val chunker = ContentChunker(maxChunkSize = 5000, overlapSize = 200, minChunkSize = 1500)
        val chunks = chunker.chunk(root)
        save(root)
        root.descendants().forEach { save(it) }
        chunks.forEach { save(it) }
        onNewRetrievables(chunks)
        createRelationships(root)
        commit()
        return chunks.map { it.id }
    }

    /**
     * Create relationships between the structural elements in this content.
     * For example, in a graph database, create relationships between documents, sections, and chunks
     * based on their ids.
     */
    protected abstract fun createRelationships(root: MaterializedDocument)

    protected abstract fun commit()

    /**
     * The chunks have been saved to the store,
     * but chunks are special and we probably want to embed them
     */
    protected abstract fun onNewRetrievables(
        retrievables: List<Retrievable>,
    )
}
