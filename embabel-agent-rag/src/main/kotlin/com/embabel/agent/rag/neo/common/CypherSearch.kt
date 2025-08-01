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
package com.embabel.boogie.neo.common

import com.embabel.agent.rag.Chunk
import com.embabel.agent.rag.MappedEntity
import com.embabel.agent.rag.NamedEntityData
import com.embabel.common.core.types.SimilarityResult
import org.slf4j.Logger

/**
 * API-independent cypher searcher
 */
interface CypherSearch {

    /**
     * Query for all entities in the knowledge graph.
     * Includes both generic entities and mapped entities.
     */
    fun queryForEntities(
        purpose: String,
        query: String,
        params: Map<String, *> = emptyMap<String, String>(),
        logger: Logger? = null,
    ): List<NamedEntityData>

    fun queryForMappedEntities(
        purpose: String,
        query: String,
        params: Map<String, Any> = emptyMap(),
        logger: Logger? = null,
    ): List<MappedEntity>

    fun chunkSimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<Chunk>>

    fun entityDataSimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *> = emptyMap<String, String>(),
        logger: Logger? = null,
    ): List<SimilarityResult<NamedEntityData>>

    fun mappedEntitySimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<out MappedEntity>>

}
