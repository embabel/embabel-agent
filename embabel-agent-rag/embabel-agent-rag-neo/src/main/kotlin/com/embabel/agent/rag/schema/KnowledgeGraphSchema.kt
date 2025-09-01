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
package com.embabel.agent.rag.schema

import com.embabel.agent.core.PropertyDefinition
import com.embabel.agent.rag.EntityData
import com.embabel.common.core.types.HasInfoString

const val ENTITY_LABEL = "Entity"

/**
 * Simple entity definition for a knowledge graph schema.
 * @param description a human-readable description of the entity type
 * @param labels a set of labels or types that this entity belongs to
 * @param properties a list of properties that this entity has
 * @param creationPermitted if false new entities of this type cannot be created
 */
data class EntityDefinition(
    val description: String,
    val labels: Set<String>,
    val properties: List<PropertyDefinition>,
    val creationPermitted: Boolean = true,
) : HasInfoString {

    val type get() = labels.firstOrNull() ?: ENTITY_LABEL

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return """
            EntityDefinition(type='$type', description='$description', labels=$labels, properties=${properties.size})
        """.trimIndent()
    }
}

/**
 * Relationship cardinality
 */
enum class Cardinality {
    ONE,
    MANY,
}

data class RelationshipDefinition(
    val sourceLabel: String,
    val targetLabel: String,
    val type: String,
    val description: String,
    val cardinality: Cardinality = Cardinality.ONE,
) : HasInfoString {

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return """
            RelationshipDefinition(sourceEntity='$sourceLabel', targetEntity='$targetLabel', type='$type', cardinality=$cardinality, description='$description')
        """.trimIndent()
    }
}

/**
 * Knowledge graph schema
 */
data class KnowledgeGraphSchema(
    val entities: List<EntityDefinition>,
    val relationships: List<RelationshipDefinition>,
) : HasInfoString {

    /**
     * Give these entities, return a list of relationships that can exist between them.
     */
    fun possibleRelationshipsBetween(entities: List<EntityData>): List<RelationshipDefinition> {
        return relationships.filter { relationship ->
            entities.any { it.labels.contains(relationship.sourceLabel) } &&
                    entities.any { it.labels.contains(relationship.targetLabel) }
        }
    }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return """
            |Schema with ${entities.size} entities and ${relationships.size} relationships:
            |Entities:
            |${entities.joinToString("\n") { "\t${it.infoString(verbose)} " }}
            |Relationships:
            |${relationships.joinToString("\n") { "\t${it.infoString(verbose)} " }}
            """.trimMargin()
    }
}
