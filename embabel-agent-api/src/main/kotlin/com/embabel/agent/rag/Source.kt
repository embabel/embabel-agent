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

import com.embabel.agent.domain.library.HasContent
import com.embabel.common.util.indent
import java.util.*

/**
 * A Source object instance is an input
 * such as a Chunk or a Fact.
 */
sealed interface Source : Retrievable

/**
 * Implemented by objects that are from a source.
 */
interface Sourced {
    val basis: Retrievable
}

interface HierarchicalContentElement : ContentElement {

    val url: String?

    val parentId: String?

}

interface ContentRoot : HierarchicalContentElement {
    val title: String
    override val parentId get() = null
}

sealed interface Section : HierarchicalContentElement {
    val title: String
}

interface MaterializedSection : Section

interface ContainerSection : Section

interface MaterializedContainerSection : Section {

    val children: List<MaterializedSection>

    fun descendants(): List<MaterializedSection> =
        children + children.filterIsInstance<MaterializedContainerSection>().flatMap { containerChild ->
            containerChild.descendants()
        }

    fun leaves(): List<LeafSection> =
        children.filterIsInstance<LeafSection>() +
                children.filterIsInstance<MaterializedContainerSection>().flatMap { containerChild ->
                    containerChild.leaves()
                }
}

data class DefaultMaterializedContainerSection(
    override val id: String,
    override val url: String? = null,
    override val title: String,
    override val children: List<MaterializedSection>,
    override val parentId: String? = null,
    override val metadata: Map<String, Any?> = emptyMap(),
) : ContainerSection, MaterializedContainerSection

data class MaterializedContentRoot(
    override val id: String,
    override val url: String? = null,
    override val title: String,
    override val children: List<MaterializedSection>,
    override val metadata: Map<String, Any?> = emptyMap(),
) : MaterializedContainerSection, ContentRoot


data class LeafSection(
    override val id: String,
    override val url: String? = null,
    override val title: String,
    override val content: String,
    override val parentId: String? = null,
    override val metadata: Map<String, Any?> = emptyMap(),
) : MaterializedSection, HasContent

/**
 * Traditional RAG. Text chunk
 */
interface Chunk : Source, HierarchicalContentElement {

    /**
     * Text content
     */
    val text: String

    override val url: String? get() = metadata["url"] as? String

    override fun embeddableValue(): String = text

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
    override val metadata: Map<String, Any?> = emptyMap(),
    override val id: String = UUID.randomUUID().toString(),
) : Source {

    override fun embeddableValue(): String = assertion

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "Fact $id from $authority: $assertion".indent(indent)
}
