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

import com.embabel.common.util.indent
import java.util.*

/**
 * A Source object instance is an input
 * such as a Chunk or a Fact.
 * It was provided to the system as data;
 * it is not inferred by the system, but a direct piece of data.
 */
sealed interface Source : Retrievable

/**
 * Implemented by objects that are from a source.
 */
interface Sourced {
    val basis: Retrievable
}


/**
 * Traditional RAG. Text chunk
 */
interface Chunk : Source, HierarchicalContentElement {

    /**
     * Text content
     */
    val text: String

    override val uri: String? get() = metadata["url"] as? String

    override fun embeddableValue(): String = text

    override fun propertiesToPersist(): Map<String, Any?> {
        return super<HierarchicalContentElement>.propertiesToPersist() + mapOf(
            "text" to text,
        )
    }

    override fun labels(): Set<String> {
        return super<Source>.labels() + super<HierarchicalContentElement>.labels() + setOf("Chunk")
    }

    fun transform(transformed: String): Chunk =
        ChunkImpl(
            id = this.id,
            text = transformed,
            metadata = this.metadata,
        )

    companion object {

        operator fun invoke(
            id: String,
            text: String,
            metadata: Map<String, Any?> = emptyMap(),
            parentId: String? = null,
        ): Chunk {
            return ChunkImpl(
                id = id,
                text = text,
                metadata = metadata,
                parentId = parentId,
            )
        }

    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "chunk: $text".indent(indent)
}

private data class ChunkImpl(
    override val id: String,
    override val text: String,
    override val parentId: String? = null,
    override val metadata: Map<String, Any?>,
) : Chunk

/**
 * A fact.
 * @param assertion the text of the fact
 * @param authority the authority of the fact, such as a person
 */
data class Fact(
    val assertion: String,
    val authority: String,
    override val uri: String? = null,
    override val metadata: Map<String, Any?> = emptyMap(),
    override val id: String = UUID.randomUUID().toString(),
) : Source {

    override fun embeddableValue(): String = assertion

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "Fact $id from $authority: $assertion".indent(indent)
}
