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
package com.embabel.agent.rag.neo.common

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.EntityData
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.neo.ogm.OgmMappedEntity
import com.embabel.common.core.types.SimilarityResult
import org.neo4j.ogm.model.Result
import org.slf4j.Logger

/**
 * API-independent cypher searcher
 * Must work within Spring's transaction management
 */
interface CypherSearch {

    fun createEntity(
        entity: NamedEntityData,
        basis: Retrievable,
    ): String

    /**
     * Load a mapped entity with OGM.
     * @return null if not found
     */
    fun <T> loadEntity(
        type: Class<T>,
        id: String,
    ): T?

    /**
     * Query for all entities in the knowledge graph.
     * Includes both generic entities and mapped entities.
     * The query must return entities as n.
     */
    fun queryForEntities(
        purpose: String,
        query: String,
        params: Map<String, *> = emptyMap<String, String>(),
        logger: Logger? = null,
    ): List<EntityData>

    /**
     * Query for mapped entities in the knowledge graph.
     * The query must return entities as n.
     */
    fun queryForMappedEntities(
        purpose: String,
        query: String,
        params: Map<String, Any> = emptyMap(),
        logger: Logger? = null,
    ): List<OgmMappedEntity>

    fun chunkSimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<out Chunk>>

    fun entityDataSimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *> = emptyMap<String, String>(),
        logger: Logger? = null,
    ): List<SimilarityResult<out EntityData>>

    fun chunkFullTextSearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<out Chunk>>

    fun entityFullTextSearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<out EntityData>>

    fun queryForInt(
        query: String,
        params: Map<String, *> = emptyMap<String, String>(),
    ): Int

    fun query(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger? = null,
    ): QueryResult
}
