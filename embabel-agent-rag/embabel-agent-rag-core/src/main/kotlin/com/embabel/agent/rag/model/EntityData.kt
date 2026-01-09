/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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
package com.embabel.agent.rag.model

import com.embabel.common.util.indent
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Any retrievable entity, whether mapped or generic.
 */
interface RetrievableEntity : Retrievable {

    override fun labels(): Set<String> {
        return super.labels() + setOf(ENTITY_LABEL)
    }

    companion object {
        /**
         * Base label for all entities.
         * Aligns with Neo4j LLM Graph Builder convention.
         * @see <a href="https://github.com/neo4j-labs/llm-graph-builder">LLM Graph Builder</a>
         */
        const val ENTITY_LABEL = "__Entity__"

        /**
         * Relationship type from Chunk to Entity.
         * Aligns with Neo4j LLM Graph Builder convention.
         * ```
         * (Chunk)-[:HAS_ENTITY]->(__Entity__)
         * ```
         */
        const val HAS_ENTITY = "HAS_ENTITY"
    }
}

/**
 * Generic retrieved entity
 */
interface EntityData : RetrievableEntity {

    @get:Schema(
        description = "Properties of this object. Arbitrary key-value pairs, although likely specified in schema. Must filter out embedding",
        example = "{\"birthYear\": 1854, \"deathYear\": 1930}",
        required = true,
    )
    val properties: Map<String, Any>

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val labelsString = labels().joinToString(":")
        return "(${labelsString} id='$id')".indent(indent)
    }

    override fun embeddableValue(): String {
        val props = properties.entries
            .filterNot { DEFAULT_EXCLUDED_PROPERTIES.contains(it.key) }
            .joinToString { (k, v) -> "$k=$v" }
        return "Entity {${labels()}}: properties=[$props]"
    }

    companion object {
        val DEFAULT_EXCLUDED_PROPERTIES = setOf("embedding", "id")
    }
}

data class SimpleEntityData(
    override val id: String,
    override val uri: String? = null,
    val labels: Set<String>,
    override val properties: Map<String, Any>,
    override val metadata: Map<String, Any?> = emptyMap(),
) : EntityData {

    override fun labels() = labels + super.labels()

}
