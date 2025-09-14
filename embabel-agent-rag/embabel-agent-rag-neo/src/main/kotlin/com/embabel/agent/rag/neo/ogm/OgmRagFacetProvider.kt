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

import com.embabel.agent.rag.*
import com.embabel.agent.rag.ingestion.DefaultMaterializedContainerSection
import com.embabel.agent.rag.ingestion.MaterializedDocument
import com.embabel.agent.rag.neo.common.CypherQuery
import com.embabel.agent.rag.schema.SchemaResolver
import com.embabel.agent.rag.support.FunctionRagFacet
import com.embabel.agent.rag.support.RagFacet
import com.embabel.agent.rag.support.RagFacetProvider
import com.embabel.agent.rag.support.RagFacetResults
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.neo4j.ogm.session.SessionFactory
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate


/**
 * Performs RAG queries in readonly transactions using Neo4j OGM.
 * Requires a Neo4j OGM PlatformTransactionManager to be configured in the Spring context.
 */
@Service
class OgmRagFacetProvider(
    private val modelProvider: ModelProvider,
    private val ogmCypherSearch: OgmCypherSearch,
    private val schemaResolver: SchemaResolver,
    private val sessionFactory: SessionFactory,
    platformTransactionManager: PlatformTransactionManager,
    private val properties: NeoRagServiceProperties,
) : AbstractWritableContentElementRepository(properties), RagFacetProvider {

    private val logger = LoggerFactory.getLogger(OgmRagFacetProvider::class.java)

    private val readonlyTransactionTemplate = TransactionTemplate(platformTransactionManager).apply {
        isReadOnly = true
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
    }

    override val name = properties.name


    private val embeddingService = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria)

    override fun provision() {
        logger.info("Provisioning with properties {}", properties)
        // TODO do we want this on ContentElement?
        createVectorIndex(properties.contentElementIndex, "Chunk")
        createVectorIndex(properties.entityIndex, properties.entityNodeName)
        createFullTextIndex(properties.contentElementFullTextIndex, "Chunk", listOf("text"))
        createFullTextIndex(properties.entityFullTextIndex, properties.entityNodeName, listOf("name", "description"))
        logger.info("Provisioning complete")
    }

    override fun findChunksById(chunkIds: List<String>): List<Chunk> {
        val session = ogmCypherSearch.currentSession()
        val rows = session.query(
            cypherContentElementQuery(" WHERE c:Chunk AND c.id IN \$ids "),
            mapOf("ids" to chunkIds),
            true,
        )
        return rows.map(::rowToContentElement).filterIsInstance<Chunk>()
    }

    override fun findById(id: String): ContentElement? {
        return findChunksById(listOf(id)).firstOrNull()
    }

    override fun save(element: ContentElement): ContentElement {
        ogmCypherSearch.query(
            "Save element",
            query = "save_content_element",
            params = mapOf(
                "id" to element.id,
                "labels" to element.labels(),
                "properties" to element.propertiesToPersist(),
            )
        )
        return element
    }

    fun findAll(): List<ContentElement> {
        val rows = ogmCypherSearch.currentSession().query(
            cypherContentElementQuery(""),
            emptyMap<String, Any?>(),
            true,
        )
        return rows.map(::rowToContentElement)
    }

    override fun count(): Int {
        return ogmCypherSearch.queryForInt("MATCH (c:ContentElement) RETURN count(c) AS count")
    }

    private fun cypherContentElementQuery(whereClause: String): String =
        "MATCH (c:ContentElement) $whereClause RETURN c.id AS id, c.uri as uri, c.text AS text, c.parentId as parentId, c.metadata.source as metadata_source, labels(c) as labels"

    private fun rowToContentElement(row: Map<String, Any?>): ContentElement {
        val metadata = mutableMapOf<String, Any>()
        metadata["source"] = row["metadata_source"] ?: "unknown"
        val labels = row["labels"] as? Array<String> ?: error("Must have labels")
        if (labels.contains("Chunk"))
            return Chunk(
                id = row["id"] as String,
                text = row["text"] as String,
                parentId = row["parentId"] as String,
                metadata = metadata,
            )
        if (labels.contains("Document"))
            return MaterializedDocument(
                id = row["id"] as String,
                title = row["id"] as String,
                children = emptyList(),
                metadata = metadata,
                uri = row["uri"] as String,
            )
        if (labels.contains("LeafSection"))
            return LeafSection(
                id = row["id"] as String,
                title = row["id"] as String,
                text = row["text"] as String,
                parentId = row["parentId"] as String,
                metadata = metadata,
                uri = row["uri"] as String?,
            )
        if (labels.contains("Section"))
            return DefaultMaterializedContainerSection(
                id = row["id"] as String,
                title = row["id"] as String,
                parentId = row["parentId"] as String?,
                // TODO we don't care about this
                children = emptyList(),
                metadata = metadata,
                uri = row["uri"] as String?,
            )
        error("Unknown ContentElement type with labels: ${labels.joinToString(",")}")
    }

    override fun commit() {
        // No-op for OGM as we use transactions
    }

    override fun onNewRetrievables(retrievables: List<Retrievable>) {
        retrievables.forEach { embedRetrievable(it) }
    }

    private fun embedRetrievable(
        retrievable: Retrievable,
    ) {
        val embedding = embeddingService.model.embed(retrievable.embeddableValue())
        val cypher = """
                MERGE (n:${retrievable.labels().joinToString(":")} {id: ${'$'}id})
                SET n.embedding = ${'$'}embedding,
                 n.embeddingModel = ${'$'}embeddingModel,
                 n.embeddedAt = timestamp()
                RETURN COUNT(n) as nodesUpdated
               """.trimIndent()
        val params = mapOf(
            "id" to retrievable.id,
            "embedding" to embedding,
            "embeddingModel" to embeddingService.name,
        )
        val result = ogmCypherSearch.query(
            purpose = "embedding",
            query = cypher,
            params = params,
        )
        val propertiesSet = result.queryStatistics().propertiesSet
        if (propertiesSet != 1) {
            logger.warn(
                "Expected to set 1 embedding property, but set: {}. chunkId={}, cypher={}",
                propertiesSet,
                retrievable.id,
                cypher,
            )
        }
    }

    override fun createRelationships(root: MaterializedDocument) {
        ogmCypherSearch.query(
            "Create relationships for root ${root.id}",
            query = "create_content_element_relationships",
            params = mapOf(
                "rootId" to root.id,
            )
        )
    }

    override fun accept(t: List<Document>) {
        TODO("Not yet implemented")
    }

    override fun facets(): List<RagFacet<out Retrievable>> {
        return listOf(
            FunctionRagFacet(
                name = "OgmRagService",
                searchFunction = ::search,
            )
        )
    }

    fun search(ragRequest: RagRequest): RagFacetResults<Retrievable> {
        val embedding = embeddingService.model.embed(ragRequest.query)
        val allResults = mutableListOf<SimilarityResult<out Retrievable>>()

        val commonParameters = mapOf(
            "topK" to ragRequest.topK,
            "similarityThreshold" to ragRequest.similarityThreshold,
        )

        if (ragRequest.entitySearch != null) {
            val cypherResults = generateAndExecuteCypher(ragRequest, ragRequest.entitySearch!!).also { cypherResults ->
                logger.info("{} Cypher results for query '{}'", cypherResults.size, ragRequest.query)
            }
            allResults += cypherResults
        } else {
            logger.info("No entity search specified, skipping Cypher execution")
        }

//        val genericEntityResults = queryRunner.entityDataSimilaritySearch(
//            "searchEntities",
//            query = "entity_vector_search",
//            params = mapOf(
//                "queryVector" to embedding,
//                "topK" to ragRequest.topK,
//                "similarityThreshold" to ragRequest.similarityThreshold,
//            ),
//        )

        return readonlyTransactionTemplate.execute {

            val chunkResults = ogmCypherSearch.chunkSimilaritySearch(
                "Chunk similarity search",
                query = "chunk_vector_search",
                params = commonParameters + mapOf(
                    "vectorIndex" to properties.contentElementIndex,
                    "queryVector" to embedding,
                ),
                logger = logger,
            )
            logger.info("{} chunk similarity results for query '{}'", chunkResults.size, ragRequest.query)
            allResults += chunkResults

            val chunkFullTextResults = ogmCypherSearch.chunkFullTextSearch(
                purpose = "Chunk full text search",
                query = "chunk_fulltext_search",
                params = commonParameters + mapOf(
                    "fulltextIndex" to properties.contentElementFullTextIndex,
                    "searchText" to "\"${ragRequest.query}\"",
                ),
                logger = logger,
            )
            logger.info("{} chunk full-text results for query '{}'", chunkFullTextResults.size, ragRequest.query)
            allResults += chunkFullTextResults

            if (ragRequest.entitySearch != null) {
                val entityResults = ogmCypherSearch.mappedEntitySimilaritySearch(
                    purpose = "Mapped entity search",
                    query = "entity_vector_search",
                    params = commonParameters + mapOf(
                        "index" to properties.entityIndex,
                        "queryVector" to embedding,
                    ),
                    logger,
                )
                allResults += entityResults
                logger.info("{} mapped entity results for query '{}'", entityResults.size, ragRequest.query)
                val entityFullTextResults = ogmCypherSearch.entityFullTextSearch(
                    purpose = "Entity full text search",
                    query = "entity_fulltext_search",
                    params = commonParameters + mapOf(
                        "fulltextIndex" to properties.entityFullTextIndex,
                        "searchText" to ragRequest.query,
                    ),
                    logger = logger,
                )
                logger.info("{} entity full-text results for query '{}'", entityFullTextResults.size, ragRequest.query)
                allResults += entityFullTextResults
            }

            // TODO should reward multiple matches
            val mergedResults: List<SimilarityResult<out Retrievable>> = allResults
                .distinctBy { it.match.id }
                .sortedByDescending { it.score }
                .take(ragRequest.topK)
            RagFacetResults(
                facetName = this.name,
                results = mergedResults,
            )
        } ?: run {
            logger.error("Transaction failed or returned null, returning empty RagResponse")
            RagFacetResults(
                facetName = this.name,
                results = emptyList(),
            )
        }
    }

    private fun generateAndExecuteCypher(
        request: RagRequest,
        entitySearch: EntitySearch,
    ): List<SimilarityResult<out Retrievable>> {
        val schema = schemaResolver.getSchema(entitySearch)
        if (schema == null) {
            logger.info("No schema found for entity search {}, skipping Cypher execution", entitySearch)
            return emptyList()
        }

        val cypherRagQueryGenerator = SchemaDrivenCypherRagQueryGenerator(
            modelProvider,
            schema,
        )
        val cypher = cypherRagQueryGenerator.generateQuery(request = request)
        logger.info("Generated Cypher query: $cypher")

        val cypherResults = readonlyTransactionTemplate.execute {
            executeGeneratedCypher(cypher)
        } ?: Result.failure(
            IllegalStateException("Transaction failed or returned null while executing Cypher query: $cypher")
        )
        if (cypherResults.isSuccess) {
            val results = cypherResults.getOrThrow()
            if (results.isNotEmpty()) {
                logger.info("Cypher query executed successfully, results: {}", results)
                return results.map {
                    // Most similar as we found them by a query
                    SimpleSimilaritySearchResult(
                        it,
                        score = 1.0,
                    )
                }
            }
        }
        return emptyList()
    }

    /**
     * Execute generate Cypher query, being sure to handle exceptions gracefully.
     */
    private fun executeGeneratedCypher(
        query: CypherQuery,
    ): Result<List<OgmMappedNamedAndDescribedEntity>> {
        try {
            return Result.success(
                ogmCypherSearch.queryForMappedEntities(
                    purpose = "cypherGeneratedQuery",
                    query = query.query
                )
            )
        } catch (e: Exception) {
            logger.error("Error executing generated query: $query", e)
            return Result.failure(e)
        }
    }

    private fun createVectorIndex(
        name: String,
        on: String,
    ) {
        sessionFactory.openSession().query(
            """
            CREATE VECTOR INDEX `$name` IF NOT EXISTS
            FOR (n:$on) ON (n.embedding)
            OPTIONS {indexConfig: {
            `vector.dimensions`: ${embeddingService.model.dimensions()},
            `vector.similarity_function`: 'cosine'
            }}""", emptyMap<String, Any>()
        )
    }

    private fun createFullTextIndex(
        name: String,
        on: String,
        properties: List<String>,
    ) {
        val propertiesString = properties.joinToString(", ") { "n.$it" }
        sessionFactory.openSession().query(
            """|
                |CREATE FULLTEXT INDEX `$name` IF NOT EXISTS
                |FOR (n:$on) ON EACH [$propertiesString]
                |OPTIONS {
                |indexConfig: {
                |
                |   }
                |}
                """.trimMargin(),
            emptyMap<String, Any>()
        )
        logger.info("Created full-text index {} for {} on properties {}", name, on, properties)
    }
}
