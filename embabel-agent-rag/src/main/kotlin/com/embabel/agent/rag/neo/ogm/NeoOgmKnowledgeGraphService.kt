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
import com.embabel.agent.rag.repository.ChunkRepository
import com.embabel.agent.rag.schema.*
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.ModelProvider
import org.neo4j.ogm.session.Session
import org.neo4j.ogm.session.SessionFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

/**
 * @param chunkNodeName the name of the node representing a chunk in the knowledge graph
 * @param entityNodeName the name of a node representing an entity in the knowledge graph
 */
@ConfigurationProperties(prefix = "application.neo")
data class NeoOgmKnowledgeGraphServiceProperties(
    val chunkNodeName: String = "Document",
    val entityNodeName: String = "Entity",
)

/**
 * Implements several interfaces to read and write knowledge graph data to Neo4j using Neo4j OGM.
 */
@Service
class NeoOgmKnowledgeGraphService(
    private val sessionFactory: SessionFactory,
    private val queryRunner: OgmCypherSearch,
    modelProvider: ModelProvider,
    private val properties: NeoOgmKnowledgeGraphServiceProperties,
) : SchemaResolver, ChunkRepository {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val embeddingService = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria)

    override fun findAll(): List<Chunk> {
        val rows = sessionFactory.openSession().query(
            cypherChunkQuery(""),
            emptyMap<String, Any?>(),
            true,
        )
        return rows.map(::rowToChunk)
    }

    private fun cypherChunkQuery(whereClause: String): String =
        "MATCH (c:${properties.chunkNodeName}) $whereClause RETURN c.id AS id, c.text AS text, c.metadata.source as metadata_source"


    override fun findChunksById(chunkIds: List<String>): List<Chunk> {
        val rows = sessionFactory.openSession().query(
            cypherChunkQuery(" WHERE c.id IN \$ids "),
            mapOf("ids" to chunkIds),
            true,
        )
        return rows.map(::rowToChunk)
    }

    private fun rowToChunk(row: Map<String, Any?>): Chunk {
        val metadata = mutableMapOf<String, Any>()
        metadata["source"] = row["metadata_source"] ?: "unknown"
        return Chunk(
            id = row["id"] as String,
            text = row["text"] as String,
            metadata = metadata,
        )
    }

    // TODO is not using name
    override fun getSchema(name: String): KnowledgeGraphSchema {
        val metadata = sessionFactory.metaData()
        val relationships = mutableListOf<RelationshipDefinition>()
        val entityDefinitions = metadata.persistentEntities()
            .filter { it.hasPrimaryIndexField() }
            .map { entity ->
                val labels = entity.staticLabels().toSet()
                val entityDefinition = EntityDefinition(
                    labels = labels,
                    properties = emptyList(),
                    description = labels.joinToString(","),
                )
                entity.relationshipFields().forEach { relationshipField ->
                    val targetEntity = relationshipField.typeDescriptor.split(".").last()
                    relationships.add(
                        RelationshipDefinition(
                            sourceLabel = entityDefinition.type,
                            targetLabel = targetEntity,
                            type = relationshipField.relationship(),
                            description = relationshipField.name,
                            cardinality = if (relationshipField.isArray || relationshipField.isIterable) {
                                Cardinality.MANY
                            } else {
                                Cardinality.ONE
                            },
                        )
                    )
                }
                entityDefinition
            }
        return KnowledgeGraphSchema(
            entities = entityDefinitions,
            relationships = relationships,
        )
    }


    private fun createEntity(
        session: Session,
        entity: NamedEntityData,
        basis: Retrievable,
    ) {
        val params = mapOf(
            "id" to entity.id,
            "name" to entity.name,
            "description" to entity.description,
            "basisId" to basis.id,
            "properties" to entity.properties,
            "chunkNodeName" to properties.chunkNodeName,
            "entityLabels" to entity.labels + properties.entityNodeName,
        )
        val result = queryRunner.query(
            purpose = "Merge entity",
            query = "create_entity",
            params = params,
            session = session,
            logger = logger,
        )
        if (result.queryStatistics().nodesCreated != 1) {
            logger.warn(
                "Expected to create 1 node, but created: {}. params={}",
                result.queryStatistics().nodesCreated,
                params
            )
        }
    }

    private fun embedEntities(
        session: Session,
        entities: List<EntityData>
    ) {
        entities.forEach { entity ->
            embedEntity(session, entity)
        }
    }

    private fun embedEntity(
        session: Session,
        entity: EntityData
    ) {
        val embedding = embeddingService.model.embed(entity.embeddableValue())
        val cypher = """
                MERGE (n:${entity.labels.joinToString(":")} {id: ${'$'}entityId})
                SET n.embedding = ${'$'}embedding
                RETURN COUNT(n) as nodesUpdated
               """.trimIndent()
        logger.info("Executing embedding cypher: {}", cypher)
        session.query(
            cypher,
            mapOf(
                "entityId" to entity.id,
                "embedding" to embedding,
            )
        )

    }
}

fun EntityData.neoLabels(): String {
    return labels.joinToString(":")
}
