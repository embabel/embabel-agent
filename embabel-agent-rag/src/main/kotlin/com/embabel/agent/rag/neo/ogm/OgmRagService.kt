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

import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.neo.common.CypherQuery
import com.embabel.agent.rag.schema.SchemaResolver
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class OgmRagService(
    private val modelProvider: ModelProvider,
    private val queryRunner: OgmCypherSearch,
    private val schemaResolver: SchemaResolver,
) : RagService {

    private val logger = LoggerFactory.getLogger(OgmRagService::class.java)

    override val name = "OgmRagService"

    override val description = "RAG service using Neo4j OGM for querying and embedding"

    private val embeddingService = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria)

    override fun search(ragRequest: RagRequest): RagResponse {
        // TODO this is wrong. Need a better way of determining the schema.
        val schema = schemaResolver.getSchema("default")

        val embedding = embeddingService.model.embed(ragRequest.query)
        val cypherRagQueryGenerator = SchemaDrivenCypherRagQueryGenerator(
            modelProvider,
            // TODO hard coded schema
            schemaResolver.getSchema("any")
        )
        val cypher = cypherRagQueryGenerator.generateQuery(
            request = ragRequest,
        )
        logger.info("Generated Cypher query: $cypher")

        val cypherResults = executeGeneratedQuery(cypher)
        if (cypherResults.isSuccess) {
            val results = cypherResults.getOrThrow()
            if (results.isNotEmpty()) {
                logger.info("Cypher query executed successfully, results: {}", results)
                return RagResponse(
                    service = this.name,
                    results = results.map {
                        // Most similar as we found them by a query
                        SimpleSimilaritySearchResult(
                            it,
                            score = 1.0,
                        )
                    },
                )
            }
        }

        val genericEntityResults = queryRunner.entityDataSimilaritySearch(
            "searchEntities",
            query = "entity_vector_search",
            params = mapOf(
                "queryVector" to embedding,
                "topK" to ragRequest.topK,
                "similarityThreshold" to ragRequest.similarityThreshold,
            ),
        )
        val chunkResults = queryRunner.chunkSimilaritySearch(
            "searchChunks",
            query = "chunk_vector_search",
            params = mapOf(
                "queryVector" to embedding,
                "topK" to 2,
                "similarityThreshold" to 0.0,
            ),
            logger = logger,
        )
        val entityResults = queryRunner.mappedEntitySimilaritySearch(
            purpose = "searchMappedEntities",
            query = "entity_vector_search",
            params = mapOf(
                "queryVector" to embedding,
                "topK" to ragRequest.topK,
                "similarityThreshold" to ragRequest.similarityThreshold,
            ),
            logger,
        )
        return RagResponse(
            service = this.name,
            results = chunkResults + entityResults,
        )
    }

    /**
     * Execute generate Cypher query, being sure to handle exceptions gracefully.
     */
    private fun executeGeneratedQuery(
        query: CypherQuery,
    ): Result<List<OgmMappedNamedEntity>> {
        try {
            return Result.success(
                queryRunner.queryForMappedEntities(
                    purpose = "cypherGeneratedQuery",
                    query = query.query
                )
            )
        } catch (e: Exception) {
            logger.error("Error executing generated query: $query", e)
            return Result.failure(e)
        }
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "OgmRagService: name=$name, description=$description, embeddingService=${
            embeddingService.infoString(
                verbose
            )
        }"
    }
}
