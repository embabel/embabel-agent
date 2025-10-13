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

import com.embabel.agent.core.SimplePropertyDefinition
import com.embabel.agent.rag.EntitySearch
import com.embabel.agent.rag.schema.*
import com.fasterxml.jackson.annotation.JsonClassDescription
import org.neo4j.ogm.session.SessionFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service


/**
 * Infers schema from OGM metadata
 */
@Service
class OgmMetadataSchemaResolver(
    private val sessionFactory: SessionFactory,
    private val ogmCypherSearch: OgmCypherSearch,
    private val properties: NeoRagServiceProperties,
) : SchemaResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    // TODO is not using name
    // TODO not filtering entities
    override fun getSchema(entitySearch: EntitySearch): KnowledgeGraphSchema? {
        val metadata = sessionFactory.metaData()
        val relationships = mutableListOf<RelationshipDefinition>()
        val entityDefinitions = metadata.persistentEntities()
            .filter { it.hasPrimaryIndexField() }
            .map { entity ->
                val classDescription = entity.underlyingClass.getAnnotation(JsonClassDescription::class.java)?.value
                val creationPermitted =
                    entity.underlyingClass.getAnnotation(CreationPermitted::class.java)?.value != false
                val labels = entity.staticLabels().toSet()
                val entityDefinition = EntityDefinition(
                    labels = labels,
                    properties = entity.propertyFields().map { property ->
                        SimplePropertyDefinition(
                            name = property.name,
                            type = property.typeDescriptor,
                            description = property.name, // TODO get from annotation
                        )
                    },
                    description = classDescription ?: labels.joinToString(","),
                    creationPermitted = creationPermitted,
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
        if (entityDefinitions.size == 2 && relationships.isEmpty()) {
            // Special case of superclasses only
            return null
        }
        return KnowledgeGraphSchema(
            entities = entityDefinitions,
            relationships = relationships,
        )
    }

}
