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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.common.ai.model.EmbeddingService

/**
 * Consistent interface for building SearchOperations instances.
 * Implemented by builders for specific SearchOperations types,
 * which can extend this interface to add more configuration methods.
 * @param T the type of SearchOperations being built
 * @param THIS the concrete builder type, for fluent method chaining
 */
interface SearchOperationsBuilder<T : SearchOperations, THIS : SearchOperationsBuilder<T, THIS>> {

    fun withName(name: String): THIS

    fun withEmbeddingService(embeddingService: EmbeddingService): THIS

    /**
     * Build the SearchOperations instance.
     * Return an instance which is ready to use.
     */
    fun build(): T
}

/**
 * Extension of [SearchOperationsBuilder] for builders that support
 * configuring content chunking and ingestion.
 */
interface IngestingSearchOperationsBuilder<T : CoreSearchOperations, THIS : IngestingSearchOperationsBuilder<T, THIS>> :
    SearchOperationsBuilder<T, THIS> {

    fun withChunkerConfig(chunkerConfig: ContentChunker.Config): THIS
}
