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
import com.embabel.agent.rag.RetrievableEntity
import com.embabel.common.core.types.NamedAndDescribed
import org.neo4j.ogm.annotation.Id
import org.neo4j.ogm.annotation.NodeEntity
import org.neo4j.ogm.annotation.Relationship

@NodeEntity(label = "Chunk")
class MappedChunk(
    @Id
    override val id: String,
    override val uri: String? = null,
    override val text: String,
    override val parentId: String? = null,
    override var metadata: Map<String, Any?> = emptyMap(),
) : Chunk {

    private constructor() : this(id = "", text = "")

    override fun withMetadata(metadata: Map<String, Any?>): Chunk {
        this.metadata = metadata
        return this
    }
}

/**
 * Superclass for all entities that are mapped using Neo4j OGM.
 */
@NodeEntity("Entity")
open class OgmMappedEntity(
    @Id
    override val id: String,
    override val uri: String? = null,
    @Relationship(type = "HAS_ENTITY", direction = Relationship.Direction.INCOMING)
    val chunks: List<MappedChunk> = emptyList(),
) : RetrievableEntity {

    override fun labels() =
        setOf(javaClass.simpleName) + super.labels()

    override val metadata: Map<String, Any?>
        get() = emptyMap()

    override fun toString() = infoString(verbose = false)

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "${javaClass.simpleName}:(${labels().joinToString(":")} id='$id')"
    }

    override fun embeddableValue(): String = error("Should not be called on this implementation")
}

@NodeEntity
abstract class OgmMappedNamedAndDescribedEntity(
    override val name: String,
    id: String,
    uri: String? = null,
) : OgmMappedEntity(id = id, uri = uri), NamedAndDescribed {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "${javaClass.simpleName}: name=$name, description=$description, id=$id"
    }

    override fun embeddableValue(): String {
        return "${javaClass.simpleName}: name=$name, description=$description"
    }
}
