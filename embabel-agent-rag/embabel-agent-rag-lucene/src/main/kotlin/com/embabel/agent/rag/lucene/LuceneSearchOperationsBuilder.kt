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
package com.embabel.agent.rag.lucene

import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.service.IngestingSearchOperationsBuilder
import com.embabel.common.ai.model.EmbeddingService
import java.nio.file.Path

/**
 * Builder for LuceneSearchOperations instances.
 */
data class LuceneSearchOperationsBuilder(
    private val name: String = "lucene-search",
    private val embeddingService: EmbeddingService? = null,
    private val chunkerConfig: ContentChunker.Config = ContentChunker.DefaultConfig(),
    private val indexPath: Path? = null,
) : IngestingSearchOperationsBuilder<LuceneSearchOperations, LuceneSearchOperationsBuilder> {

    override fun withName(name: String): LuceneSearchOperationsBuilder = copy(name = name)

    override fun withEmbeddingService(embeddingService: EmbeddingService): LuceneSearchOperationsBuilder =
        copy(embeddingService = embeddingService)

    /**
     * Sets the path where the Lucene index will be stored.
     * If not set, storage will be in memory only.
     */
    fun withIndexPath(indexPath: Path): LuceneSearchOperationsBuilder =
        copy(indexPath = indexPath)

    override fun withChunkerConfig(chunkerConfig: ContentChunker.Config): LuceneSearchOperationsBuilder =
        copy(chunkerConfig = chunkerConfig)

    override fun build(): LuceneSearchOperations {
        val luceneSearchOperations = LuceneSearchOperations(
            name = name,
            embeddingService = embeddingService,
            indexPath = indexPath,
            chunkerConfig = chunkerConfig,
        )
        luceneSearchOperations.provision()
        return luceneSearchOperations
    }

    /**
     * Builds the LuceneSearchOperations and loads existing chunks from disk.
     */
    fun buildAndLoadChunks(): LuceneSearchOperations {
        val luceneSearchOperations = build()
        luceneSearchOperations.loadExistingChunksFromDisk()
        return luceneSearchOperations
    }
}
