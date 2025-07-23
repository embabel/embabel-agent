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
package com.embabel.agent.rag

import com.embabel.common.core.types.Described
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Any retrievable entity, whether mapped or generic.
 */
interface RetrievableEntity : Retrievable {

    /**
     * Labels of the entity. In Neo, this might include multiple labels.
     * In a relational database, this might be a single table name.
     */
    @get:Schema(
        description = "Labels of the entity. In Neo, this might include multiple labels. In a relational database, this might be a single table name.",
        example = "[\"Person\", \"Customer\"]",
        required = true,
    )
    val labels: Set<String>

    /**
     * EmbeddableValue defaults to infoString
     */
    override fun embeddableValue(): String {
        return infoString(verbose = true)
    }
}

/**
 * Generic retrieved entity
 */
interface EntityData : RetrievableEntity, Described {

    @get:Schema(
        description = "description of this entity",
        example = "A customer of Acme Industries named Melissa Bell",
        required = true,
    )
    override val description: String

    @get:Schema(
        description = "Properties of this object. Arbitrary key-value pairs, although likely specified in schema. Must filter out embedding",
        example = "{\"birthYear\": 1854, \"deathYear\": 1930}",
        required = true,
    )
    val properties: Map<String, Any>

    override fun infoString(verbose: Boolean?): String {
        val labelsString = labels.joinToString(":")
        return "(${labelsString} id='$id')"
    }

}

/**
 * Entity mapped with JPA, Neo OGM or another persistence tool. Will be a JVM object.
 * What it exposes beyond RetrievableEntity methods is a matter for the RagService in the application.
 * MappedEntity objects have their own distinct types and can expose
 * @Tool methods for LLMs.
 */
interface MappedEntity : RetrievableEntity
