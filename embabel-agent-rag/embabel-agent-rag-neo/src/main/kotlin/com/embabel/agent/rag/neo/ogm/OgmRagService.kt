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
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.ModelProvider
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
class OgmRagService(
    private val modelProvider: ModelProvider,
    private val ogmCypherSearch: OgmCypherSearch,
    private val schemaResolver: SchemaResolver,
    private val sessionFactory: SessionFactory,
    platformTransactionManager: PlatformTransactionManager,
    private val properties: NeoRagServiceProperties = NeoRagServiceProperties(),
) : AbstractWritableRagService() {

    private val logger = LoggerFactory.getLogger(OgmRagService::class.java)

    private val readonlyTransactionTemplate = TransactionTemplate(platformTransactionManager).apply {
        isReadOnly = true
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
    }

    override val name = properties.name

    override val description = properties.description

    private val embeddingService = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria)

    override fun provision() {
        logger.info("Provisioning OgmRagService with properties {}", properties)
        // TODO do we want this on ContentElement?
        createVectorIndex(properties.contentElementIndex, "Chunk")
        createVectorIndex(properties.entityIndex, properties.entityNodeName)
        logger.info("Provisioned OgmRagService")
    }

    override fun findChunksById(chunkIds: List<String>): List<Chunk> {
        val session = ogmCypherSearch.currentSession()
        val rows = session.query(
            cypherContentElementQuery(" WHERE c.id IN \$ids "),
            mapOf("ids" to chunkIds),
            true,
        )
        // TODO inefficient: Filter in DB
        return rows.map(::rowToContentElement).filterIsInstance<Chunk>()
    }

    override fun findById(id: String): ContentElement? {
        return findChunksById(listOf(id)).firstOrNull()
    }

    override fun save(element: ContentElement): ContentElement {
        ogmCypherSearch.query(
            "Save an element with labels ${element.labels().joinToString(",")}",
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

    private fun cypherContentElementQuery(whereClause: String): String =
        "MATCH (c:ContentElement) $whereClause RETURN c.id AS id, c.text AS text, c.parentId as parentId, c.metadata.source as metadata_source, labels(c) as labels"


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
            )
        if (labels.contains("LeafSection"))
            return LeafSection(
                id = row["id"] as String,
                title = row["id"] as String,
                text = row["text"] as String,
                parentId = row["parentId"] as String,
                metadata = metadata,
            )
        if (labels.contains("Section"))
            return DefaultMaterializedContainerSection(
                id = row["id"] as String,
                title = row["id"] as String,
                parentId = row["parentId"] as String?,
                // TODO we don't care about this
                children = emptyList(),
                metadata = metadata,
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
                MERGE (n:Chunk {id: ${'$'}id})
                SET n.embedding = ${'$'}embedding
                RETURN COUNT(n) as nodesUpdated
               """.trimIndent()
        val params = mapOf(
            "id" to retrievable.id,
            "embedding" to embedding,
        )
        logger.info("Executing embed entity cypher: {},\nparams={}", cypher, params)
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

    override fun search(ragRequest: RagRequest): RagResponse {
        val embedding = embeddingService.model.embed(ragRequest.query)

        // TODO this is wrong. Need a better way of determining the schema.
        val schema = schemaResolver.getSchema("default")

        if (schema !== null) {
            val cypherRagQueryGenerator = SchemaDrivenCypherRagQueryGenerator(
                modelProvider,
                schema,
            )
            val cypher = cypherRagQueryGenerator.generateQuery(
                request = ragRequest,
            )
            logger.info("Generated Cypher query: $cypher")

            val cypherResults = readonlyTransactionTemplate.execute { executeGeneratedQuery(cypher) } ?: Result.failure(
                IllegalStateException("Transaction failed or returned null while executing Cypher query: $cypher")
            )
            if (cypherResults.isSuccess) {
                val results = cypherResults.getOrThrow()
                if (results.isNotEmpty()) {
                    logger.info("Cypher query executed successfully, results: {}", results)
                    return RagResponse(
                        request = ragRequest,
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
                "searchChunks",
                query = "chunk_vector_search",
                params = mapOf(
                    "vectorIndex" to properties.contentElementIndex,
                    "queryVector" to embedding,
                    "topK" to ragRequest.topK,
                    "similarityThreshold" to ragRequest.similarityThreshold,
                ),
                logger = logger,
            )
            val entityResults = ogmCypherSearch.mappedEntitySimilaritySearch(
                purpose = "searchMappedEntities",
                query = "entity_vector_search",
                params = mapOf(
                    "index" to properties.entityIndex,
                    "queryVector" to embedding,
                    "topK" to ragRequest.topK,
                    "similarityThreshold" to ragRequest.similarityThreshold,
                ),
                logger,
            )
            RagResponse(
                request = ragRequest,
                service = this.name,
                results = chunkResults + entityResults,
            )
        } ?: run {
            logger.error("Transaction failed or returned null, returning empty RagResponse")
            RagResponse(
                request = ragRequest,
                service = this.name,
                results = emptyList(),
            )
        }
    }

    /**
     * Execute generate Cypher query, being sure to handle exceptions gracefully.
     */
    private fun executeGeneratedQuery(
        query: CypherQuery,
    ): Result<List<OgmMappedNamedEntity>> {
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
}
