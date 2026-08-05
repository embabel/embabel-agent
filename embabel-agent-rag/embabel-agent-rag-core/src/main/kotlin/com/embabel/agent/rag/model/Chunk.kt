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
import java.util.*

/**
 * Traditional RAG. Text chunk.
 */
interface Chunk : Source, HierarchicalContentElement {

    /**
     * Text content that will be indexed.
     */
    val text: String

    /**
     * Raw content. Text will differ if any processing (e.g. cleaning, normalization) was applied.
     * This is important for citation
     */
    val urtext: String

    /**
     * Chunker-owned structural fields (document/section ids, sequence, indexes).
     * Free-form user data remains in [metadata]; structural keys are also exposed there
     * for backward compatibility during the deprecation window.
     */
    val structure: ChunkStructure

    /**
     * Parent must be non-null. It must a physical content element.
     */
    override val parentId: String

    /**
     * If available, this is the path from the root document as ids,
     * with the root id first and this element's id as the last element.
     *
     * Default implementation uses [structure] (with metadata fallback via the compat view).
     * For a more robust solution that traverses actual parentId relationships,
     * use ContentElementRepository.pathFromRoot(element) instead.
     *
     * Return null if not available or any part of the path is missing.
     */
    val pathFromRoot: List<String>?
        get() {
            val rootId = structure.rootDocumentId ?: return null
            val containerId = structure.containerSectionId
            val leafId = structure.leafSectionId

            val path = mutableListOf<String>()
            path.add(rootId)

            if (containerId != null && containerId != rootId) {
                path.add(containerId)
            }

            if (leafId != null && leafId != containerId) {
                path.add(leafId)
            }

            if (id != path.lastOrNull()) {
                path.add(id)
            }

            return path
        }

    override val uri: String? get() = metadata["url"] as? String

    override fun embeddableValue(): String = text

    override fun propertiesToPersist(): Map<String, Any?> {
        return super<HierarchicalContentElement>.propertiesToPersist() + mapOf(
            "text" to text,
            "urtext" to urtext,
        )
    }

    override fun labels(): Set<String> {
        return super<Source>.labels() + super<HierarchicalContentElement>.labels() + setOf("Chunk")
    }

    /**
     * Transform the content of this chunk
     */
    fun withText(transformed: String): Chunk =
        create(
            text = transformed,
            parentId = parentId,
            metadata = metadata,
            id = id,
            urtext = urtext,
            structure = structure,
        )

    /**
     * Replace structural fields while preserving free-form metadata.
     */
    fun withStructure(structure: ChunkStructure): Chunk =
        create(
            text = text,
            parentId = parentId,
            metadata = ChunkStructure.withoutStructuralKeys(metadata),
            id = id,
            urtext = urtext,
            structure = structure,
        )

    companion object {

        operator fun invoke(
            id: String,
            text: String,
            metadata: Map<String, Any?>,
            parentId: String,
        ): Chunk {
            return create(
                text = text,
                parentId = parentId,
                metadata = metadata,
                id = id,
                urtext = text,
            )
        }

        @JvmOverloads
        @JvmStatic
        fun create(
            text: String,
            parentId: String,
            metadata: Map<String, Any?> = emptyMap(),
            id: String = UUID.randomUUID().toString(),
            urtext: String = text,
            structure: ChunkStructure? = null,
        ): Chunk {
            val resolvedStructure = structure ?: ChunkStructure.fromMetadata(metadata)
            val freeform = ChunkStructure.withoutStructuralKeys(metadata)
            return ChunkImpl(
                id = id,
                text = text,
                urtext = urtext,
                parentId = parentId,
                freeformMetadata = freeform,
                structure = resolvedStructure,
            )
        }

    }

    /**
     * Return the chunk with additional metadata.
     * May be a new instance or the same instance,
     * but must have the same id and text.
     * All existing metadata will be replaced:
     * callers are responsible for ensuring that any
     * they want to keep is preserved.
     */
    fun withAdditionalMetadata(metadata: Map<String, Any?>): Chunk

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "chunk: $text".indent(indent)
}

private data class ChunkImpl(
    override val id: String,
    override val text: String,
    override val urtext: String,
    override val parentId: String,
    private val freeformMetadata: Map<String, Any?>,
    override val structure: ChunkStructure,
) : Chunk {

    override val metadata: Map<String, Any?>
        get() {
            val structural = structure.toMetadata()
            return if (structural.isEmpty()) freeformMetadata else freeformMetadata + structural
        }

    override fun withAdditionalMetadata(metadata: Map<String, Any?>): Chunk {
        val addedStructure = ChunkStructure.fromMetadata(metadata)
        val addedFreeform = ChunkStructure.withoutStructuralKeys(metadata)
        return copy(
            freeformMetadata = freeformMetadata + addedFreeform,
            structure = structure.merge(addedStructure),
        )
    }
}
