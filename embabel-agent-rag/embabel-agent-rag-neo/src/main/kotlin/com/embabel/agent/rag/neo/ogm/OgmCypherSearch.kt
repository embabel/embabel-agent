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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.EntityData
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.model.SimpleEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.neo.common.CypherSearch
import com.embabel.agent.rag.neo.common.LogicalQueryResolver
import com.embabel.agent.rag.neo.common.QueryResult
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.ClusterFinder
import com.embabel.agent.rag.service.ClusterRetrievalRequest
import com.embabel.agent.rag.service.TypedEntitySearch
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.util.time
import org.neo4j.driver.internal.InternalNode
import org.neo4j.driver.internal.value.StringValue
import org.neo4j.ogm.model.Result
import org.neo4j.ogm.session.Session
import org.neo4j.ogm.session.SessionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.neo4j.transaction.SessionFactoryUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class OgmCypherSearch(
    private val sessionFactory: SessionFactory,
    private val queryResolver: LogicalQueryResolver,
) : CypherSearch, ClusterFinder {

    private val ogmCypherSearchLogger = LoggerFactory.getLogger(OgmCypherSearch::class.java)

    override fun createEntity(
        entity: NamedEntityData,
        basis: Retrievable,
    ): String {
        val params = mapOf(
            "id" to entity.id,
            "name" to entity.name,
            "description" to entity.description,
            "basisId" to basis.id,
            "properties" to entity.properties,
            "chunkNodeName" to "Chunk",
            "entityLabels" to entity.labels(),
        )
        val result = query(
            purpose = "Create entity",
            query = "create_entity",
            params = params,
            logger = ogmCypherSearchLogger,
        )
        if (result.items().size != 1) {
            ogmCypherSearchLogger.warn(
                "Expected to create 1 node, but created: {}. params={}",
                result.items().size,
                params
            )
        }
        val singleRow = result.singleOrNull() ?: error("No result returned from create_entity")
        val id = singleRow["id"] as? String ?: error("No id returned from create_entity")
        ogmCypherSearchLogger.info("Created entity {} with id: {}", entity.labels(), id)
        return id
    }

    override fun <T> loadEntity(
        type: Class<T>,
        id: String,
    ): T? {
        return currentSession().load(type, id)
    }

    override fun queryForEntities(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<EntityData> {
        val result = query(purpose = purpose, query = query, params = params, logger = logger)
        return rowsToEntityData(result)
    }

    @Transactional(readOnly = true)
    override fun <E> findClusters(opts: ClusterRetrievalRequest<E>): List<Cluster<E>> {
        val labels = opts.entitySearch?.labels?.toList() ?: error("Must specify labels in entity search for clustering")
        val desiredType = (opts.entitySearch as? TypedEntitySearch)?.entities?.first() ?: OgmMappedEntity::class.java
        val params = mapOf(
            "labels" to labels,
            "vectorIndex" to opts.vectorIndex,
            "similarityThreshold" to opts.similarityThreshold,
            "topK" to opts.topK,
        )
        val result = query(
            purpose = "cluster",
            query = "vector_cluster",
            params = params,
        )
        return result.map { row ->
            val anchor = row["anchor"] as E
            val similar = (row["similar"]) as Array<Map<String, Any>>
            val similarityResults = similar.mapNotNull { similarEntityMap ->
                val inode = similarEntityMap["match"] as InternalNode
                val matchId = (inode.get("id") as StringValue).asString()
                val score = similarEntityMap["score"] as Double
                val match = try {
                    currentSession().load(desiredType, matchId)
                } catch (e: Exception) {
                    ogmCypherSearchLogger.warn("Could not load entity of type $desiredType with id $matchId", e)
                    null
                }
                if (match == null) {
                    // Shouldn't happen...query is likely incorrect
                    ogmCypherSearchLogger.warn("Could not load match for $similarEntityMap, type=${desiredType}, id=$matchId")
                    null
                } else {
                    ogmCypherSearchLogger.debug("Found match: {} with score {}", match, "%.2f".format(score))
                    SimpleSimilaritySearchResult(match, score) as SimilarityResult<E>
                }
            }
            Cluster(anchor, similarityResults)
        }
    }

    override fun queryForMappedEntities(
        purpose: String,
        query: String,
        params: Map<String, Any>,
        logger: Logger?,
    ): List<OgmMappedEntity> {
        val result = query(purpose = purpose, query = query, params = params, logger = logger)
        return result.mapNotNull { row ->
            val match = row["n"] as? OgmMappedNamedAndDescribedEntity
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
    ): List<SimilarityResult<EntityData>> {
        val result = query(purpose = purpose, query = query, params = params, logger = logger)
        return rowsToSimilarityResult(result)
    }

    override fun chunkSimilaritySearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<Chunk>> {
        val result = query(purpose = purpose, query = query, params = params, logger = logger)
        return result.map { row ->
            SimpleSimilaritySearchResult(
                match = Chunk(
                    id = row["id"] as String,
                    text = row["text"] as String,
                    metadata = mapOf("source" to (row["metadata_source"] as String)),
                    parentId = row["parent_id"] as String? ?: "unknown",
                ),
                score = row["score"] as Double,
            )
        }
    }


    override fun chunkFullTextSearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<Chunk>> {
        val result = query(purpose = purpose, query = query, params = params, logger = logger)
        return result.map { row ->
            SimpleSimilaritySearchResult(
                match = Chunk(
                    id = row["id"] as String,
                    text = row["text"] as String,
                    parentId = "unknown", // parentId not available from full-text search
                    metadata = mapOf("source" to "unknown"),
                ),
                score = row["score"] as Double,
            )
        }
    }

    override fun entityFullTextSearch(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): List<SimilarityResult<EntityData>> {
        val result = query(purpose = purpose, query = query, params = params, logger = logger)
        return rowsToSimilarityResult(result)
    }

    private fun rowsToEntityData(
        result: QueryResult,
    ): List<EntityData> = result.map { row ->
        SimpleNamedEntityData(
            id = row["id"] as String,
            name = row["name"] as String,
            description = row["description"] as String? ?: "",
            labels = (row["labels"] as Array<String>).toSet(),
            properties = emptyMap(), // TODO: handle properties
        )
    }

    private fun rowsToMappedEntitySimilarityResult(
        result: Result,
    ): List<SimilarityResult<OgmMappedEntity>> = result.mapNotNull { row ->
        val match = row["match"] as? OgmMappedEntity
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
        result: QueryResult,
    ): List<SimilarityResult<EntityData>> = result.map { row ->
        val name = row["name"] as? String
        val description = row["description"] as? String
        val labels = (row["labels"] as Array<String>).toSet()
        val properties = row["properties"] as? Map<String, Any> ?: emptyMap()
        val match = if (name != null && description != null) {
            SimpleNamedEntityData(
                id = row["id"] as String,
                name = row["name"] as String,
                description = row["description"] as String,
                labels = labels,
                properties = properties,
            )
        } else {
            SimpleEntityData(
                id = row["id"] as String,
                labels = labels,
                properties = properties,
            )
        }
        SimpleSimilaritySearchResult(
            match = match,
            score = row["score"] as Double,
        )
    }

    /**
     * Get the current OGM session, which requires a transaction to be active.
     */
    fun currentSession(): Session =
        SessionFactoryUtils.getSession(sessionFactory)
            ?: error("No active OGM session found. Ensure you are within a Spring transaction context.")

    /**
     * Execute a query and return results as QueryResult
     */
    override fun query(
        purpose: String,
        query: String,
        params: Map<String, *>,
        logger: Logger?,
    ): QueryResult {
        val loggerToUse = logger ?: ogmCypherSearchLogger
        val cypher = if (query.contains(" ")) query else queryResolver.resolve(query)!!
        loggerToUse.info("[{}] query\n\tparams: {}\n{}", purpose, params, cypher)
        val (ogmResult, millis) = time {
            currentSession().query(
                cypher,
                params,
            )
        }
        loggerToUse.info("[{}] query took {} ms", purpose, millis)
        return QueryResult(ogmResult.toList())
    }

    override fun queryForInt(
        query: String,
        params: Map<String, *>,
    ): Int {
        val cypher = if (query.contains(" ")) query else queryResolver.resolve(query)!!
        val result = currentSession().query(cypher, params)
        val singleRow = result.singleOrNull() ?: return 0
        val firstValue = singleRow.values.firstOrNull() ?: return 0
        return when (firstValue) {
            is Int -> firstValue
            is Long -> firstValue.toInt()
            is Double -> firstValue.toInt()
            else -> 0
        }
    }
}
