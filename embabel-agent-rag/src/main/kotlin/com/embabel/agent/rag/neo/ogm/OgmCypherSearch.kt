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
package com.embabel.agent.rag.neo.ogm

import com.embabel.agent.rag.Chunk
import com.embabel.agent.rag.NamedEntityData
import com.embabel.agent.rag.SimpleNamedEntityData
import com.embabel.agent.rag.neo.common.CypherSearch
import com.embabel.agent.rag.neo.common.LogicalQueryResolver
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.util.time
import org.neo4j.ogm.model.Result
import org.neo4j.ogm.session.SessionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.neo4j.transaction.SessionFactoryUtils
import org.springframework.stereotype.Service


@Service
class OgmCypherSearch(
    private val sessionFactory: SessionFactory,
    private val queryResolver: LogicalQueryResolver,
) : CypherSearch {

    // TODO add doInSession if necessary

    private val ogmCypherSearchLogger = LoggerFactory.getLogger(OgmCypherSearch::class.java)

    override fun queryForEntities(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<NamedEntityData> {
        val result = query(purpose, query, params, logger = logger)
        return rowsToNamedEntityData(result)
    }

    override fun queryForMappedEntities(
        purpose: String,
        query: String,
        params: Map<String, Any>,
        logger: Logger?,
    ): List<OgmMappedNamedEntity> {
        val result = query(purpose, query, params, logger = logger)
        return result.mapNotNull { row ->
            val match = row["n"] as? OgmMappedNamedEntity
            if (match == null) {
                ogmCypherSearchLogger.warn("Match is null for row: {}", row)
                return@mapNotNull null
            }
            match
        }
    }

    override fun entityDataSimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<NamedEntityData>> {
        val result = query(purpose, query, params, logger = logger)
        return rowsToSimilarityResult(result)
    }

    override fun chunkSimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<Chunk>> {
        val result = query(purpose, query, params, logger = logger)
        return result.map { row ->
            SimpleSimilaritySearchResult(
                match = Chunk(
                    id = row["id"] as String,
                    text = row["text"] as String,
//                    metadata = mapOf("source" to (row["metadata_source"] as String)),
                ),
                score = row["score"] as Double,
            )
        }
    }

    override fun mappedEntitySimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<OgmMappedNamedEntity>> {
        val result = query(purpose, query, params, logger = logger)
        return rowsToMappedEntitySimilarityResult(result)
    }

    private fun rowsToNamedEntityData(
        result: Result,
    ): List<SimpleNamedEntityData> = result.map { row ->
        SimpleNamedEntityData(
            id = row["id"] as String,
            name = row["name"] as String,
            description = row["description"] as String,
            labels = (row["labels"] as Array<String>).toSet(),
            properties = emptyMap(), // TODO: handle properties
        )
    }

    private fun rowsToMappedEntitySimilarityResult(
        result: Result,
    ): List<SimilarityResult<OgmMappedNamedEntity>> = result.mapNotNull { row ->
        val match = row["match"] as? OgmMappedNamedEntity
        if (match == null) {
            ogmCypherSearchLogger.warn("Match is null for row: $row")
            return@mapNotNull null
        }
        SimpleSimilaritySearchResult(
            match = match,
            score = row["score"] as Double,
        )
    }

    private fun rowsToSimilarityResult(
        result: Result,
    ): List<SimilarityResult<NamedEntityData>> = result.map { row ->
        SimpleSimilaritySearchResult(
            match = SimpleNamedEntityData(
                id = row["id"] as String,
                name = row["name"] as String,
                description = row["description"] as String,
                labels = (row["labels"] as Array<String>).toSet(),
                properties = emptyMap(), // TODO: handle properties
            ),
            score = row["score"] as Double,
        )
    }

    /**
     * Return an OGM result
     */
    fun query(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): Result {
        val loggerToUse = logger ?: ogmCypherSearchLogger
        val session = SessionFactoryUtils.getSession(sessionFactory)
        val cypher = if (query.contains(" ")) query else queryResolver.resolve(query)!!
        loggerToUse.info("Executing query for purpose {} with params {}\n{}", purpose, params, cypher)
        val (result, millis) = time {
            session.query(
                cypher,
                params,
            )
        }
        loggerToUse.info("Query for purpose {} took {} ms", purpose, millis)
        return result
    }

}
